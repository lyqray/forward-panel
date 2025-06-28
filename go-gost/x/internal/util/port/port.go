package port

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// ForceClosePortConnections 强制断开指定端口的所有连接
func ForceClosePortConnections(addr string) error {
	if addr == "" {
		return nil
	}

	_, portStr, err := splitHostPort(addr)
	if err != nil {
		return fmt.Errorf("parse address failed: %v", err)
	}

	port, err := strconv.Atoi(portStr)
	if err != nil {
		return fmt.Errorf("invalid port: %v", err)
	}

	if runtime.GOOS != "linux" {
		fmt.Println("Warning: current OS is not Linux, skipping connection close.")
		return nil
	}

	return closePortConnectionsBySS(port)
}

// splitHostPort 是 net.SplitHostPort 的包装，容错增强
func splitHostPort(addr string) (string, string, error) {
	if !strings.Contains(addr, ":") {
		return "", "", fmt.Errorf("missing port in address")
	}
	return netSplitHostPortCompat(addr)
}

// netSplitHostPortCompat 是增强版本的 SplitHostPort，兼容无 host 情况
func netSplitHostPortCompat(addr string) (string, string, error) {
	lastColon := strings.LastIndex(addr, ":")
	if lastColon < 0 {
		return "", "", fmt.Errorf("missing port")
	}
	return addr[:lastColon], addr[lastColon+1:], nil
}

// closePortConnectionsBySS 通过 ss 找到 pid/fd 然后关闭连接
func closePortConnectionsBySS(targetPort int) error {
	cmd := exec.Command("ss", "-tnp")
	out, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("failed to execute ss: %v", err)
	}

	scanner := bufio.NewScanner(strings.NewReader(string(out)))
	for scanner.Scan() {
		line := scanner.Text()

		if !strings.Contains(line, fmt.Sprintf(":%d", targetPort)) || !strings.Contains(line, "ESTAB") {
			continue
		}

		// 例如：users:(("gost",pid=1234,fd=17))
		start := strings.Index(line, "pid=")
		if start == -1 {
			continue
		}

		rest := line[start:]
		parts := strings.Split(rest, ",")
		if len(parts) < 2 {
			continue
		}

		pidStr := strings.TrimPrefix(parts[0], "pid=")
		fdStr := strings.TrimPrefix(parts[1], "fd=")
		pid := strings.Trim(pidStr, ")")
		fd := strings.Trim(fdStr, ")")

		fdPath := fmt.Sprintf("/proc/%s/fd/%s", pid, fd)
		realPath, err := filepath.EvalSymlinks(fdPath)
		if err != nil {
			fmt.Printf("Warning: unable to resolve %s: %v\n", fdPath, err)
			continue
		}

		// 再次确认是 socket
		if !strings.Contains(realPath, "socket:") {
			continue
		}

		// 尝试关闭 fd
		func() {
			defer func() {
				if r := recover(); r != nil {
					fmt.Printf("Warning: panic closing %s: %v\n", fdPath, r)
				}
			}()
			f, err := os.OpenFile(fdPath, os.O_RDWR, 0)
			if err != nil {
				fmt.Printf("Warning: open fd failed: %v\n", err)
				return
			}
			defer f.Close()
		}()
	}

	return scanner.Err()
}
