package socket

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync" // 新增：用于管理连接状态的互斥锁
	"time"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/gorilla/websocket"
	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/host"
	"github.com/shirou/gopsutil/v3/mem"
	psnet "github.com/shirou/gopsutil/v3/net"
)

// SystemInfo 系统信息结构体
type SystemInfo struct {
	Uptime           uint64  `json:"uptime"`            // 开机时间	（秒）
	BytesReceived    uint64  `json:"bytes_received"`    // 接收字节数
	BytesTransmitted uint64  `json:"bytes_transmitted"` // 发送字节数
	CPUUsage         float64 `json:"cpu_usage"`         // CPU使用率（百分比）
	MemoryUsage      float64 `json:"memory_usage"`      // 内存使用率（百分比）
}

// NetworkStats 网络统计信息
type NetworkStats struct {
	BytesReceived    uint64 `json:"bytes_received"`    // 接收字节数
	BytesTransmitted uint64 `json:"bytes_transmitted"` // 发送字节数
}

// CPUInfo CPU信息
type CPUInfo struct {
	Usage float64 `json:"usage"` // CPU使用率（百分比）
}

// MemoryInfo 内存信息
type MemoryInfo struct {
	Usage float64 `json:"usage"` // 内存使用率（百分比）
}

// CommandMessage 命令消息结构体
type CommandMessage struct {
	Type      string      `json:"type"`
	Data      interface{} `json:"data"`
	RequestId string      `json:"requestId,omitempty"`
}

// CommandResponse 命令响应结构体
type CommandResponse struct {
	Type      string      `json:"type"`
	Success   bool        `json:"success"`
	Message   string      `json:"message"`
	Data      interface{} `json:"data,omitempty"`
	RequestId string      `json:"requestId,omitempty"`
}

// PingRequest ping请求结构体
type PingRequest struct {
	IP        string `json:"ip"`
	Count     int    `json:"count"`
	RequestId string `json:"requestId,omitempty"`
}

// PingResponse ping响应结构体
type PingResponse struct {
	IP           string  `json:"ip"`
	Success      bool    `json:"success"`
	AverageTime  float64 `json:"averageTime"` // 平均延迟(ms)
	PacketLoss   float64 `json:"packetLoss"`  // 丢包率(%)
	ErrorMessage string  `json:"errorMessage,omitempty"`
	RequestId    string  `json:"requestId,omitempty"`
}

type WebSocketReporter struct {
	url            string
	conn           *websocket.Conn
	reconnectTime  time.Duration
	pingInterval   time.Duration
	configInterval time.Duration
	ctx            context.Context
	cancel         context.CancelFunc
	connected      bool
	connecting     bool              // 新增：正在连接状态
	connMutex      sync.Mutex        // 新增：连接状态锁
	aesCrypto      *crypto.AESCrypto // 新增：AES加密器
}

// NewWebSocketReporter 创建一个新的WebSocket报告器
func NewWebSocketReporter(serverURL string, secret string) *WebSocketReporter {
	ctx, cancel := context.WithCancel(context.Background())

	// 创建 AES 加密器
	aesCrypto, err := crypto.NewAESCrypto(secret)
	if err != nil {
		fmt.Printf("❌ 创建 AES 加密器失败: %v\n", err)
		aesCrypto = nil
	} else {
		fmt.Printf("🔐 AES 加密器创建成功\n")
	}

	return &WebSocketReporter{
		url:            serverURL,
		reconnectTime:  5 * time.Second,  // 重连间隔
		pingInterval:   2 * time.Second,  // 发送间隔改为2秒
		configInterval: 10 * time.Minute, // 配置上报间隔
		ctx:            ctx,
		cancel:         cancel,
		connected:      false,
		connecting:     false,
		aesCrypto:      aesCrypto,
	}
}

// Start 启动WebSocket报告器
func (w *WebSocketReporter) Start() {
	go w.run()
}

// Stop 停止WebSocket报告器
func (w *WebSocketReporter) Stop() {
	w.cancel()
	if w.conn != nil {
		w.conn.Close()
	}

}

// run 主运行循环
func (w *WebSocketReporter) run() {
	for {
		select {
		case <-w.ctx.Done():
			return
		default:
			// 检查连接状态，避免重复连接
			w.connMutex.Lock()
			needConnect := !w.connected && !w.connecting
			w.connMutex.Unlock()

			if needConnect {
				if err := w.connect(); err != nil {
					fmt.Printf("❌ WebSocket连接失败: %v，%v后重试\n", err, w.reconnectTime)
					select {
					case <-time.After(w.reconnectTime):
						continue
					case <-w.ctx.Done():
						return
					}
				}
			}

			// 连接成功，开始发送消息
			if w.connected {
				w.handleConnection()
			} else {
				// 如果连接失败，等待重试
				select {
				case <-time.After(w.reconnectTime):
					continue
				case <-w.ctx.Done():
					return
				}
			}
		}
	}
}

// connect 建立WebSocket连接
func (w *WebSocketReporter) connect() error {
	w.connMutex.Lock()
	defer w.connMutex.Unlock()

	// 如果已经在连接中或已连接，直接返回
	if w.connecting || w.connected {
		return nil
	}

	// 设置连接中状态
	w.connecting = true
	defer func() {
		w.connecting = false
	}()

	u, err := url.Parse(w.url)
	if err != nil {
		return fmt.Errorf("解析URL失败: %v", err)
	}

	dialer := websocket.DefaultDialer
	dialer.HandshakeTimeout = 10 * time.Second

	conn, _, err := dialer.Dial(u.String(), nil)
	if err != nil {
		return fmt.Errorf("连接WebSocket失败: %v", err)
	}

	// 如果在连接过程中已经有连接了，关闭新连接
	if w.conn != nil && w.connected {
		conn.Close()
		return nil
	}

	w.conn = conn
	w.connected = true

	// 设置关闭处理器来检测连接状态
	w.conn.SetCloseHandler(func(code int, text string) error {
		w.connMutex.Lock()
		w.connected = false
		w.connMutex.Unlock()
		return nil
	})

	fmt.Printf("✅ WebSocket连接建立成功\n")
	return nil
}

// handleConnection 处理WebSocket连接
func (w *WebSocketReporter) handleConnection() {
	defer func() {
		w.connMutex.Lock()
		if w.conn != nil {
			w.conn.Close()
			w.conn = nil
		}
		w.connected = false
		w.connMutex.Unlock()
		fmt.Printf("🔌 WebSocket连接已关闭\n")
	}()

	// 启动消息接收goroutine
	go w.receiveMessages()

	// 主发送循环
	ticker := time.NewTicker(w.pingInterval)
	defer ticker.Stop()

	for {
		select {
		case <-w.ctx.Done():
			return
		case <-ticker.C:
			// 检查连接状态
			w.connMutex.Lock()
			isConnected := w.connected
			w.connMutex.Unlock()

			if !isConnected {
				return
			}

			// 获取系统信息并发送
			sysInfo := w.collectSystemInfo()
			if err := w.sendSystemInfo(sysInfo); err != nil {
				fmt.Printf("❌ 发送系统信息失败: %v，准备重连\n", err)
				return
			}
		}
	}
}

// collectSystemInfo 收集系统信息
func (w *WebSocketReporter) collectSystemInfo() SystemInfo {
	networkStats := getNetworkStats()
	cpuInfo := getCPUInfo()
	memoryInfo := getMemoryInfo()

	return SystemInfo{
		Uptime:           getUptime(),
		BytesReceived:    networkStats.BytesReceived,
		BytesTransmitted: networkStats.BytesTransmitted,
		CPUUsage:         cpuInfo.Usage,
		MemoryUsage:      memoryInfo.Usage,
	}
}

// sendSystemInfo 发送系统信息
func (w *WebSocketReporter) sendSystemInfo(sysInfo SystemInfo) error {
	w.connMutex.Lock()
	defer w.connMutex.Unlock()

	if w.conn == nil || !w.connected {
		return fmt.Errorf("连接未建立")
	}

	// 转换为JSON
	jsonData, err := json.Marshal(sysInfo)
	if err != nil {
		return fmt.Errorf("序列化系统信息失败: %v", err)
	}

	var messageData []byte

	// 如果有加密器，则加密数据
	if w.aesCrypto != nil {
		encryptedData, err := w.aesCrypto.Encrypt(jsonData)
		if err != nil {
			fmt.Printf("⚠️ 加密失败，发送原始数据: %v\n", err)
			messageData = jsonData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			messageData, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密消息失败，发送原始数据: %v\n", err)
				messageData = jsonData
			}
		}
	} else {
		messageData = jsonData
	}

	// 设置写入超时
	w.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))

	if err := w.conn.WriteMessage(websocket.TextMessage, messageData); err != nil {
		w.connected = false // 标记连接已断开
		return fmt.Errorf("写入消息失败: %v", err)
	}

	return nil
}

// receiveMessages 接收服务端发送的消息
func (w *WebSocketReporter) receiveMessages() {
	for {
		select {
		case <-w.ctx.Done():
			return
		default:
			w.connMutex.Lock()
			conn := w.conn
			connected := w.connected
			w.connMutex.Unlock()

			if conn == nil || !connected {
				return
			}

			// 设置读取超时
			conn.SetReadDeadline(time.Now().Add(30 * time.Second))

			messageType, message, err := conn.ReadMessage()
			if err != nil {
				if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
					fmt.Printf("❌ WebSocket读取消息错误: %v\n", err)
				}
				w.connMutex.Lock()
				w.connected = false
				w.connMutex.Unlock()
				return
			}

			// 处理接收到的消息
			w.handleReceivedMessage(messageType, message)
		}
	}
}

// handleReceivedMessage 处理接收到的消息
func (w *WebSocketReporter) handleReceivedMessage(messageType int, message []byte) {
	switch messageType {
	case websocket.TextMessage:
		// 先检查是否是加密消息
		var encryptedWrapper struct {
			Encrypted bool   `json:"encrypted"`
			Data      string `json:"data"`
			Timestamp int64  `json:"timestamp"`
		}

		// 尝试解析为加密消息格式
		if err := json.Unmarshal(message, &encryptedWrapper); err == nil && encryptedWrapper.Encrypted {
			if w.aesCrypto != nil {
				// 解密数据
				decryptedData, err := w.aesCrypto.Decrypt(encryptedWrapper.Data)
				if err != nil {
					fmt.Printf("❌ 解密失败: %v\n", err)
					w.sendErrorResponse("DecryptError", fmt.Sprintf("解密失败: %v", err))
					return
				}
				message = decryptedData
			} else {
				fmt.Printf("❌ 收到加密消息但没有加密器\n")
				w.sendErrorResponse("NoDecryptor", "没有可用的解密器")
				return
			}
		}
		// 先尝试解析是否是压缩消息
		var compressedMsg struct {
			Type       string          `json:"type"`
			Compressed bool            `json:"compressed"`
			Data       json.RawMessage `json:"data"`
			RequestId  string          `json:"requestId,omitempty"`
		}

		if err := json.Unmarshal(message, &compressedMsg); err == nil && compressedMsg.Compressed {
			// 处理压缩消息
			fmt.Printf("📥 收到压缩消息，正在解压...\n")

			// 解压数据
			gzipReader, err := gzip.NewReader(bytes.NewReader(compressedMsg.Data))
			if err != nil {
				fmt.Printf("❌ 创建解压读取器失败: %v\n", err)
				w.sendErrorResponse("DecompressError", fmt.Sprintf("解压失败: %v", err))
				return
			}
			defer gzipReader.Close()

			var decompressedData bytes.Buffer
			if _, err := decompressedData.ReadFrom(gzipReader); err != nil {
				fmt.Printf("❌ 解压数据失败: %v\n", err)
				w.sendErrorResponse("DecompressError", fmt.Sprintf("解压失败: %v", err))
				return
			}

			// 使用解压后的数据继续处理
			message = decompressedData.Bytes()

			// 构建解压后的命令消息
			var cmdMsg CommandMessage
			cmdMsg.Type = compressedMsg.Type
			cmdMsg.RequestId = compressedMsg.RequestId
			if err := json.Unmarshal(message, &cmdMsg.Data); err != nil {
				fmt.Printf("❌ 解析解压后的命令数据失败: %v\n", err)
				w.sendErrorResponse("ParseError", fmt.Sprintf("解析命令失败: %v", err))
				return
			}

			if cmdMsg.Type != "call" {
				w.routeCommand(cmdMsg)
			}
		} else {
			// 处理普通消息
			var cmdMsg CommandMessage
			if err := json.Unmarshal(message, &cmdMsg); err != nil {
				fmt.Printf("❌ 解析命令消息失败: %v\n", err)
				w.sendErrorResponse("ParseError", fmt.Sprintf("解析命令失败: %v", err))
				return
			}
			if cmdMsg.Type != "call" {
				w.routeCommand(cmdMsg)
			}
		}

	default:
		fmt.Printf("📨 收到未知类型消息: %d\n", messageType)
	}
}

// routeCommand 路由命令到对应的处理函数
func (w *WebSocketReporter) routeCommand(cmd CommandMessage) {
	jsonBytes, errs := json.Marshal(cmd)
	if errs != nil {
		fmt.Println("Error marshaling JSON:", errs)
		return
	}

	fmt.Println("🔔 收到命令: ", string(jsonBytes))
	var err error
	var response CommandResponse

	// 传递 requestId
	response.RequestId = cmd.RequestId

	switch cmd.Type {
	// Service 相关命令
	case "AddService":
		err = w.handleAddService(cmd.Data)
		response.Type = "AddServiceResponse"
	case "UpdateService":
		err = w.handleUpdateService(cmd.Data)
		response.Type = "UpdateServiceResponse"
	case "DeleteService":
		err = w.handleDeleteService(cmd.Data)
		response.Type = "DeleteServiceResponse"
	case "PauseService":
		err = w.handlePauseService(cmd.Data)
		response.Type = "PauseServiceResponse"
	case "ResumeService":
		err = w.handleResumeService(cmd.Data)
		response.Type = "ResumeServiceResponse"

	// Chain 相关命令
	case "AddChains":
		err = w.handleAddChain(cmd.Data)
		response.Type = "AddChainsResponse"
	case "UpdateChains":
		err = w.handleUpdateChain(cmd.Data)
		response.Type = "UpdateChainsResponse"
	case "DeleteChains":
		err = w.handleDeleteChain(cmd.Data)
		response.Type = "DeleteChainsResponse"

	// Limiter 相关命令
	case "AddLimiters":
		err = w.handleAddLimiter(cmd.Data)
		response.Type = "AddLimitersResponse"
	case "UpdateLimiters":
		err = w.handleUpdateLimiter(cmd.Data)
		response.Type = "UpdateLimitersResponse"
	case "DeleteLimiters":
		err = w.handleDeleteLimiter(cmd.Data)
		response.Type = "DeleteLimitersResponse"

	// Ping 诊断命令
	case "Ping":
		var pingResult PingResponse
		pingResult, err = w.handlePing(cmd.Data)
		response.Type = "PingResponse"
		response.Data = pingResult

	default:
		err = fmt.Errorf("未知命令类型: %s", cmd.Type)
		response.Type = "UnknownCommandResponse"
	}

	// 发送响应
	if err != nil {
		saveConfig()
		response.Success = false
		response.Message = err.Error()
	} else {
		saveConfig()
		response.Success = true
		response.Message = "OK"
	}

	w.sendResponse(response)
}

// Service 命令处理函数
func (w *WebSocketReporter) handleAddService(data interface{}) error {
	// 将 interface{} 转换为 JSON 再解析为具体类型
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 预处理：将字符串格式的 duration 转换为纳秒数
	processedData, err := w.preprocessDurationFields(jsonData)
	if err != nil {
		return fmt.Errorf("预处理duration字段失败: %v", err)
	}

	var services []config.ServiceConfig
	if err := json.Unmarshal(processedData, &services); err != nil {
		return fmt.Errorf("解析服务配置失败: %v", err)
	}

	req := createServicesRequest{Data: services}
	return createServices(req)
}

func (w *WebSocketReporter) handleUpdateService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 预处理：将字符串格式的 duration 转换为纳秒数
	processedData, err := w.preprocessDurationFields(jsonData)
	if err != nil {
		return fmt.Errorf("预处理duration字段失败: %v", err)
	}

	var services []config.ServiceConfig
	if err := json.Unmarshal(processedData, &services); err != nil {
		return fmt.Errorf("解析服务配置失败: %v", err)
	}

	req := updateServicesRequest{Data: services}
	return updateServices(req)
}

func (w *WebSocketReporter) handleDeleteService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var req deleteServicesRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析删除请求失败: %v", err)
	}

	return deleteServices(req)
}

func (w *WebSocketReporter) handlePauseService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var req pauseServicesRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析暂停请求失败: %v", err)
	}

	return pauseServices(req)
}

func (w *WebSocketReporter) handleResumeService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var req resumeServicesRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析恢复请求失败: %v", err)
	}

	return resumeServices(req)
}

// Chain 命令处理函数
func (w *WebSocketReporter) handleAddChain(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var chainConfig config.ChainConfig
	if err := json.Unmarshal(jsonData, &chainConfig); err != nil {
		return fmt.Errorf("解析链配置失败: %v", err)
	}

	req := createChainRequest{Data: chainConfig}
	return createChain(req)
}

func (w *WebSocketReporter) handleUpdateChain(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 对于更新操作，Java端发送的格式可能是: {"chain": "name", "data": {...}}
	var updateReq struct {
		Chain string             `json:"chain"`
		Data  config.ChainConfig `json:"data"`
	}

	// 尝试解析为更新请求格式
	if err := json.Unmarshal(jsonData, &updateReq); err != nil {
		// 如果失败，可能是直接的ChainConfig，从name字段获取chain名称
		var chainConfig config.ChainConfig
		if err := json.Unmarshal(jsonData, &chainConfig); err != nil {
			return fmt.Errorf("解析链配置失败: %v", err)
		}
		updateReq.Chain = chainConfig.Name
		updateReq.Data = chainConfig
	}

	req := updateChainRequest{
		Chain: updateReq.Chain,
		Data:  updateReq.Data,
	}
	return updateChain(req)
}

func (w *WebSocketReporter) handleDeleteChain(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 删除操作可能是: {"chain": "name"} 或者直接是链名称字符串
	var deleteReq deleteChainRequest

	// 尝试解析为删除请求格式
	if err := json.Unmarshal(jsonData, &deleteReq); err != nil {
		// 如果失败，可能是字符串格式的名称
		var chainName string
		if err := json.Unmarshal(jsonData, &chainName); err != nil {
			return fmt.Errorf("解析链删除请求失败: %v", err)
		}
		deleteReq.Chain = chainName
	}

	return deleteChain(deleteReq)
}

// Limiter 命令处理函数
func (w *WebSocketReporter) handleAddLimiter(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var limiterConfig config.LimiterConfig
	if err := json.Unmarshal(jsonData, &limiterConfig); err != nil {
		return fmt.Errorf("解析限流器配置失败: %v", err)
	}

	req := createLimiterRequest{Data: limiterConfig}
	return createLimiter(req)
}

func (w *WebSocketReporter) handleUpdateLimiter(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 对于更新操作，Java端发送的格式可能是: {"limiter": "name", "data": {...}}
	var updateReq struct {
		Limiter string               `json:"limiter"`
		Data    config.LimiterConfig `json:"data"`
	}

	// 尝试解析为更新请求格式
	if err := json.Unmarshal(jsonData, &updateReq); err != nil {
		// 如果失败，可能是直接的LimiterConfig，从name字段获取limiter名称
		var limiterConfig config.LimiterConfig
		if err := json.Unmarshal(jsonData, &limiterConfig); err != nil {
			return fmt.Errorf("解析限流器配置失败: %v", err)
		}
		updateReq.Limiter = limiterConfig.Name
		updateReq.Data = limiterConfig
	}

	req := updateLimiterRequest{
		Limiter: updateReq.Limiter,
		Data:    updateReq.Data,
	}
	return updateLimiter(req)
}

func (w *WebSocketReporter) handleDeleteLimiter(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 删除操作可能是: {"limiter": "name"} 或者直接是限流器名称字符串
	var deleteReq deleteLimiterRequest

	// 尝试解析为删除请求格式
	if err := json.Unmarshal(jsonData, &deleteReq); err != nil {
		// 如果失败，可能是字符串格式的名称
		var limiterName string
		if err := json.Unmarshal(jsonData, &limiterName); err != nil {
			return fmt.Errorf("解析限流器删除请求失败: %v", err)
		}
		deleteReq.Limiter = limiterName
	}

	return deleteLimiter(deleteReq)
}

// handleCall 处理服务端的call回调消息
func (w *WebSocketReporter) handleCall(data interface{}) error {
	// 解析call数据
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化call数据失败: %v", err)
	}

	// 可以根据call的具体内容进行不同的处理
	var callData map[string]interface{}
	if err := json.Unmarshal(jsonData, &callData); err != nil {
		return fmt.Errorf("解析call数据失败: %v", err)
	}

	fmt.Printf("🔔 收到服务端call回调: %v\n", callData)

	// 根据call的类型执行不同的操作
	if callType, exists := callData["type"]; exists {
		switch callType {
		case "ping":
			fmt.Printf("📡 收到ping，发送pong回应\n")
			// 可以在这里发送pong响应
		case "info_request":
			fmt.Printf("📊 服务端请求额外信息\n")
			// 可以在这里发送额外的系统信息
		case "command":
			fmt.Printf("⚡ 服务端发送执行命令\n")
			// 可以在这里执行特定命令
		default:
			fmt.Printf("❓ 未知的call类型: %v\n", callType)
		}
	}

	// 简单返回成功，表示call已被处理
	return nil
}

// sendResponse 发送响应消息到服务端
func (w *WebSocketReporter) sendResponse(response CommandResponse) {
	w.connMutex.Lock()
	defer w.connMutex.Unlock()

	if w.conn == nil || !w.connected {
		fmt.Printf("❌ 无法发送响应：连接未建立\n")
		return
	}

	jsonData, err := json.Marshal(response)
	if err != nil {
		fmt.Printf("❌ 序列化响应失败: %v\n", err)
		return
	}

	var messageData []byte

	// 如果有加密器，则加密数据
	if w.aesCrypto != nil {
		encryptedData, err := w.aesCrypto.Encrypt(jsonData)
		if err != nil {
			fmt.Printf("⚠️ 加密响应失败，发送原始数据: %v\n", err)
			messageData = jsonData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			messageData, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密响应失败，发送原始数据: %v\n", err)
				messageData = jsonData
			}
		}
	} else {
		messageData = jsonData
	}

	// 检查消息大小，如果超过10MB则记录警告
	if len(messageData) > 10*1024*1024 {
		fmt.Printf("⚠️ 响应消息过大 (%.2f MB)，可能会被拒绝\n", float64(len(messageData))/(1024*1024))
	}

	// 设置较长的写入超时，以应对大消息
	timeout := 5 * time.Second
	if len(messageData) > 1024*1024 {
		timeout = 30 * time.Second
	}

	w.conn.SetWriteDeadline(time.Now().Add(timeout))
	if err := w.conn.WriteMessage(websocket.TextMessage, messageData); err != nil {
		fmt.Printf("❌ 发送响应失败: %v\n", err)
		w.connected = false
	}
}

// sendErrorResponse 发送错误响应
func (w *WebSocketReporter) sendErrorResponse(responseType, message string) {
	response := CommandResponse{
		Type:    responseType,
		Success: false,
		Message: message,
	}
	w.sendResponse(response)
}

// getUptime 获取系统开机时间（秒）
func getUptime() uint64 {
	uptime, err := host.Uptime()
	if err != nil {
		return 0
	}
	return uptime
}

// getNetworkStats 获取网络统计信息
func getNetworkStats() NetworkStats {
	var stats NetworkStats

	ioCounters, err := psnet.IOCounters(true)
	if err != nil {
		fmt.Printf("获取网络统计失败: %v\n", err)
		return stats
	}

	// 汇总所有非回环接口的流量
	for _, io := range ioCounters {
		// 跳过回环接口
		if io.Name == "lo" || strings.HasPrefix(io.Name, "lo") {
			continue
		}

		stats.BytesReceived += io.BytesRecv
		stats.BytesTransmitted += io.BytesSent
	}

	return stats
}

// getCPUInfo 获取CPU信息
func getCPUInfo() CPUInfo {
	var cpuInfo CPUInfo

	// 获取CPU使用率
	percentages, err := cpu.Percent(time.Second, false)
	if err == nil && len(percentages) > 0 {
		cpuInfo.Usage = percentages[0]
	}

	return cpuInfo
}

// getMemoryInfo 获取内存信息
func getMemoryInfo() MemoryInfo {
	var memInfo MemoryInfo

	vmStat, err := mem.VirtualMemory()
	if err != nil {
		return memInfo
	}

	memInfo.Usage = vmStat.UsedPercent

	return memInfo
}

// StartWebSocketReporterWithConfig 使用配置启动WebSocket报告器
func StartWebSocketReporterWithConfig(Addr string, Secret string, Version string) *WebSocketReporter {

	// 构建包含本机IP的WebSocket URL
	var fullURL = "ws://" + Addr + "/system-info?type=1&secret=" + Secret + "&version=" + Version

	fmt.Printf("🔗 WebSocket连接URL: %s\n", fullURL)

	reporter := NewWebSocketReporter(fullURL, Secret) // Pass Secret here
	reporter.Start()
	return reporter
}

// handlePing 处理ping诊断命令
func (w *WebSocketReporter) handlePing(data interface{}) (PingResponse, error) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return PingResponse{}, fmt.Errorf("序列化ping数据失败: %v", err)
	}

	var req PingRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return PingResponse{}, fmt.Errorf("解析ping请求失败: %v", err)
	}

	// 验证IP地址格式
	if net.ParseIP(req.IP) == nil && !isValidHostname(req.IP) {
		return PingResponse{
			IP:           req.IP,
			Success:      false,
			ErrorMessage: "无效的IP地址或主机名",
			RequestId:    req.RequestId,
		}, nil
	}

	// 设置默认ping次数
	if req.Count <= 0 {
		req.Count = 4
	}

	// 执行ping操作
	avgTime, packetLoss, err := pingHost(req.IP, req.Count)

	response := PingResponse{
		IP:        req.IP,
		RequestId: req.RequestId,
	}

	if err != nil {
		response.Success = false
		response.ErrorMessage = err.Error()
	} else {
		response.Success = true
		response.AverageTime = avgTime
		response.PacketLoss = packetLoss
	}

	return response, nil
}

// pingHost 执行ping操作，返回平均延迟和丢包率
func pingHost(ip string, count int) (float64, float64, error) {
	var cmd *exec.Cmd

	// 根据操作系统选择不同的ping命令
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("ping", "-n", strconv.Itoa(count), ip)
	case "darwin", "linux":
		cmd = exec.Command("ping", "-c", strconv.Itoa(count), ip)
	default:
		return 0, 0, fmt.Errorf("不支持的操作系统: %s", runtime.GOOS)
	}

	output, err := cmd.Output()
	if err != nil {
		return 0, 0, fmt.Errorf("ping命令执行失败: %v", err)
	}

	// 解析ping输出
	return parsePingOutput(string(output), runtime.GOOS)
}

// parsePingOutput 解析ping命令输出，提取平均延迟和丢包率
func parsePingOutput(output, osType string) (float64, float64, error) {
	lines := strings.Split(output, "\n")

	switch osType {
	case "windows":
		return parsePingOutputWindows(lines)
	case "darwin", "linux":
		return parsePingOutputUnix(lines)
	default:
		return 0, 0, fmt.Errorf("不支持的操作系统类型")
	}
}

// parsePingOutputWindows 解析Windows系统的ping输出
func parsePingOutputWindows(lines []string) (float64, float64, error) {
	var avgTime float64
	var packetLoss float64

	for _, line := range lines {
		line = strings.TrimSpace(line)

		// 查找平均延迟 (例如: "最短 = 1ms，最长 = 2ms，平均 = 1ms")
		if strings.Contains(line, "平均") && strings.Contains(line, "ms") {
			parts := strings.Split(line, "平均 = ")
			if len(parts) > 1 {
				avgPart := strings.Split(parts[1], "ms")[0]
				if avg, err := strconv.ParseFloat(avgPart, 64); err == nil {
					avgTime = avg
				}
			}
		}

		// 查找丢包率 (例如: "丢失 = 0 (0% 丢失)")
		if strings.Contains(line, "丢失") && strings.Contains(line, "%") {
			if strings.Contains(line, "(0%") {
				packetLoss = 0
			} else {
				// 提取百分比
				start := strings.Index(line, "(")
				end := strings.Index(line, "%")
				if start != -1 && end != -1 && start < end {
					lossStr := line[start+1 : end]
					if loss, err := strconv.ParseFloat(lossStr, 64); err == nil {
						packetLoss = loss
					}
				}
			}
		}
	}

	return avgTime, packetLoss, nil
}

// parsePingOutputUnix 解析Unix系统（Linux/macOS）的ping输出
func parsePingOutputUnix(lines []string) (float64, float64, error) {
	var avgTime float64
	var packetLoss float64

	for _, line := range lines {
		line = strings.TrimSpace(line)

		// 查找统计行 (例如: "4 packets transmitted, 4 received, 0% packet loss")
		if strings.Contains(line, "packet loss") {
			parts := strings.Split(line, "%")
			if len(parts) > 0 {
				// 查找百分比前的数字
				lossStr := strings.Fields(parts[0])
				if len(lossStr) > 0 {
					if loss, err := strconv.ParseFloat(lossStr[len(lossStr)-1], 64); err == nil {
						packetLoss = loss
					}
				}
			}
		}

		// 查找往返时间统计 (例如: "round-trip min/avg/max/stddev = 0.123/0.456/0.789/0.012 ms")
		if strings.Contains(line, "round-trip") && strings.Contains(line, "=") {
			parts := strings.Split(line, "=")
			if len(parts) > 1 {
				times := strings.TrimSpace(parts[1])
				times = strings.Split(times, " ")[0] // 去掉末尾的"ms"
				timeValues := strings.Split(times, "/")
				if len(timeValues) >= 2 {
					if avg, err := strconv.ParseFloat(timeValues[1], 64); err == nil {
						avgTime = avg
					}
				}
			}
		}

		// macOS的格式可能不同，查找avg (例如: "min/avg/max/stddev = 0.123/0.456/0.789/0.012 ms")
		if strings.Contains(line, "min/avg/max") && strings.Contains(line, "=") {
			parts := strings.Split(line, "=")
			if len(parts) > 1 {
				times := strings.TrimSpace(parts[1])
				times = strings.Split(times, " ")[0] // 去掉末尾的"ms"
				timeValues := strings.Split(times, "/")
				if len(timeValues) >= 2 {
					if avg, err := strconv.ParseFloat(timeValues[1], 64); err == nil {
						avgTime = avg
					}
				}
			}
		}
	}

	return avgTime, packetLoss, nil
}

// isValidHostname 验证主机名格式
func isValidHostname(hostname string) bool {
	if len(hostname) == 0 || len(hostname) > 253 {
		return false
	}

	// 简单的主机名验证
	for _, r := range hostname {
		if !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') ||
			(r >= '0' && r <= '9') || r == '-' || r == '.') {
			return false
		}
	}

	return true
}

// preprocessDurationFields 预处理 JSON 数据中的 duration 字段
func (w *WebSocketReporter) preprocessDurationFields(jsonData []byte) ([]byte, error) {
	var rawData interface{}
	if err := json.Unmarshal(jsonData, &rawData); err != nil {
		return nil, err
	}

	// 递归处理 duration 字段
	processed := w.processDurationInData(rawData)

	return json.Marshal(processed)
}

// processDurationInData 递归处理数据中的 duration 字段
func (w *WebSocketReporter) processDurationInData(data interface{}) interface{} {
	switch v := data.(type) {
	case []interface{}:
		// 处理数组
		for i, item := range v {
			v[i] = w.processDurationInData(item)
		}
		return v
	case map[string]interface{}:
		// 处理对象
		for key, value := range v {
			if key == "selector" {
				// 处理 selector 对象中的 failTimeout
				if selectorObj, ok := value.(map[string]interface{}); ok {
					if failTimeoutVal, exists := selectorObj["failTimeout"]; exists {
						if failTimeoutStr, ok := failTimeoutVal.(string); ok {
							// 将字符串格式的 duration 转换为纳秒数
							if duration, err := time.ParseDuration(failTimeoutStr); err == nil {
								selectorObj["failTimeout"] = int64(duration)
							}
						}
					}
				}
			}
			v[key] = w.processDurationInData(value)
		}
		return v
	default:
		return v
	}
}
