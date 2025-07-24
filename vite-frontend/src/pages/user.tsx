import { useState, useEffect } from 'react';
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import { 
  Table, 
  TableHeader, 
  TableColumn, 
  TableBody, 
  TableRow, 
  TableCell 
} from "@heroui/table";
import { 
  Modal, 
  ModalContent, 
  ModalHeader, 
  ModalBody, 
  ModalFooter,
  useDisclosure 
} from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Pagination } from "@heroui/pagination";
import { Select, SelectItem } from "@heroui/select";
import { RadioGroup, Radio } from "@heroui/radio";
import { DatePicker } from "@heroui/date-picker";
import { Spinner } from "@heroui/spinner";
import { Progress } from "@heroui/progress";
import { Tabs, Tab } from "@heroui/tabs";
import toast from 'react-hot-toast';
import { 
  User, 
  UserForm, 
  UserTunnel, 
  UserTunnelForm, 
  Tunnel, 
  SpeedLimit, 
  Pagination as PaginationType 
} from '@/types';
import {
  getAllUsers,
  createUser,
  updateUser,
  deleteUser,
  getTunnelList,
  assignUserTunnel,
  getUserTunnelList,
  removeUserTunnel,
  updateUserTunnel,
  getSpeedLimitList
} from '@/api';
import { SearchIcon, PlusIcon, EditIcon, DeleteIcon, UserIcon, SettingsIcon } from '@/components/icons';
import { parseDate } from "@internationalized/date";
import AdminLayout from "@/layouts/admin";

// 工具函数
const formatFlow = (value: number, unit: string = 'bytes'): string => {
  if (unit === 'gb') {
    return `${value} GB`;
  } else {
    if (value === 0) return '0 B';
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(2)} KB`;
    if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(2)} MB`;
    return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }
};

const formatDate = (timestamp: number): string => {
  return new Date(timestamp).toLocaleString();
};

const getExpireStatus = (expTime: number) => {
  const now = Date.now();
  if (expTime < now) {
    return { color: 'danger' as const, text: '已过期' };
  }
  const diffDays = Math.ceil((expTime - now) / (1000 * 60 * 60 * 24));
  if (diffDays <= 7) {
    return { color: 'warning' as const, text: `${diffDays}天后过期` };
  }
  return { color: 'success' as const, text: '正常' };
};

// 获取用户状态（根据status字段）
const getUserStatus = (user: User) => {
  if (user.status === 1) {
    return { color: 'success' as const, text: '正常' };
  } else {
    return { color: 'danger' as const, text: '禁用' };
  }
};

const calculateUserTotalUsedFlow = (user: User): number => {
  return (user.inFlow || 0) + (user.outFlow || 0);
};

const calculateTunnelUsedFlow = (tunnel: UserTunnel): number => {
  const inFlow = tunnel.inFlow || 0;
  const outFlow = tunnel.outFlow || 0;
  
  if (tunnel.tunnelFlow === 1) {
    return outFlow; // 单向计算：只计算上传流量
  } else {
    return inFlow + outFlow; // 双向计算或默认：计算上传和下载的总流量
  }
};

export default function UserPage() {
  // 状态管理
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [pagination, setPagination] = useState<PaginationType>({
    current: 1,
    size: 10,
    total: 0
  });

  // 用户表单相关状态
  const { isOpen: isUserModalOpen, onOpen: onUserModalOpen, onClose: onUserModalClose } = useDisclosure();
  const [isEdit, setIsEdit] = useState(false);
  const [userForm, setUserForm] = useState<UserForm>({
    user: '',
    pwd: '',
    status: 1,
    flow: 100,
    num: 10,
    expTime: null,
    flowResetTime: 0
  });
  const [userFormLoading, setUserFormLoading] = useState(false);

  // 隧道权限管理相关状态
  const { isOpen: isTunnelModalOpen, onOpen: onTunnelModalOpen, onClose: onTunnelModalClose } = useDisclosure();
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [userTunnels, setUserTunnels] = useState<UserTunnel[]>([]);
  const [tunnelListLoading, setTunnelListLoading] = useState(false);
  
  // 分配新隧道权限相关状态
  const [tunnelForm, setTunnelForm] = useState<UserTunnelForm>({
    tunnelId: null,
    flow: 100,
    num: 10,
    expTime: null,
    flowResetTime: 0,
    speedId: null
  });
  const [assignLoading, setAssignLoading] = useState(false);

  // 编辑隧道权限相关状态
  const { isOpen: isEditTunnelModalOpen, onOpen: onEditTunnelModalOpen, onClose: onEditTunnelModalClose } = useDisclosure();
  const [editTunnelForm, setEditTunnelForm] = useState<UserTunnel | null>(null);
  const [editTunnelLoading, setEditTunnelLoading] = useState(false);

  // 删除确认相关状态
  const { isOpen: isDeleteModalOpen, onOpen: onDeleteModalOpen, onClose: onDeleteModalClose } = useDisclosure();
  const [userToDelete, setUserToDelete] = useState<User | null>(null);

  // 删除隧道权限确认相关状态
  const { isOpen: isDeleteTunnelModalOpen, onOpen: onDeleteTunnelModalOpen, onClose: onDeleteTunnelModalClose } = useDisclosure();
  const [tunnelToDelete, setTunnelToDelete] = useState<UserTunnel | null>(null);

  // 其他数据
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [speedLimits, setSpeedLimits] = useState<SpeedLimit[]>([]);

  // 生命周期
  useEffect(() => {
    loadUsers();
    loadTunnels();
    loadSpeedLimits();
  }, [pagination.current, pagination.size, searchKeyword]);

  // 数据加载函数
  const loadUsers = async () => {
    setLoading(true);
    try {
      const response = await getAllUsers({
        current: pagination.current,
        size: pagination.size,
        keyword: searchKeyword
      });
      
      if (response.code === 0) {
        const data = response.data || {};
        setUsers(data.records || []);
        setPagination(prev => ({
          ...prev,
          total: data.total || 0,
          current: data.current || 1,
          size: data.size || 10
        }));
      } else {
        toast.error(response.msg || '获取用户列表失败');
      }
    } catch (error) {
      toast.error('获取用户列表失败');
    } finally {
      setLoading(false);
    }
  };

  const loadTunnels = async () => {
    try {
      const response = await getTunnelList();
      if (response.code === 0) {
        setTunnels(response.data || []);
      }
    } catch (error) {
      console.error('获取隧道列表失败:', error);
    }
  };

  const loadSpeedLimits = async () => {
    try {
      const response = await getSpeedLimitList();
      if (response.code === 0) {
        setSpeedLimits(response.data || []);
      }
    } catch (error) {
      console.error('获取限速规则列表失败:', error);
    }
  };

  const loadUserTunnels = async (userId: number) => {
    setTunnelListLoading(true);
    try {
      const response = await getUserTunnelList({ userId });
      if (response.code === 0) {
        setUserTunnels(response.data || []);
      } else {
        toast.error(response.msg || '获取隧道权限列表失败');
      }
    } catch (error) {
      toast.error('获取隧道权限列表失败');
    } finally {
      setTunnelListLoading(false);
    }
  };

  // 用户管理操作
  const handleSearch = () => {
    setPagination(prev => ({ ...prev, current: 1 }));
    loadUsers();
  };

  const handleAdd = () => {
    setIsEdit(false);
    setUserForm({
      user: '',
      pwd: '',
      status: 1,
      flow: 100,
      num: 10,
      expTime: null,
      flowResetTime: 0
    });
    onUserModalOpen();
  };

  const handleEdit = (user: User) => {
    setIsEdit(true);
    setUserForm({
      id: user.id,
      name: user.name,
      user: user.user,
      pwd: '',
      status: user.status,
      flow: user.flow,
      num: user.num,
      expTime: user.expTime ? new Date(user.expTime) : null,
      flowResetTime: user.flowResetTime ?? 0
    });
    onUserModalOpen();
  };

  const handleDelete = (user: User) => {
    setUserToDelete(user);
    onDeleteModalOpen();
  };

  const handleConfirmDelete = async () => {
    if (!userToDelete) return;

    try {
      const response = await deleteUser(userToDelete.id);
      if (response.code === 0) {
        toast.success('删除成功');
        loadUsers();
        onDeleteModalClose();
        setUserToDelete(null);
      } else {
        toast.error(response.msg || '删除失败');
      }
    } catch (error) {
      toast.error('删除失败');
    }
  };

  const handleSubmitUser = async () => {
    if (!userForm.user || (!userForm.pwd && !isEdit) || !userForm.expTime) {
      toast.error('请填写完整信息');
      return;
    }

    setUserFormLoading(true);
    try {
      const submitData: any = {
        ...userForm,
        expTime: userForm.expTime.getTime()
      };

      if (isEdit && !submitData.pwd) {
        delete submitData.pwd;
      }

      const response = isEdit ? await updateUser(submitData) : await createUser(submitData);
      
      if (response.code === 0) {
        toast.success(isEdit ? '更新成功' : '创建成功');
        onUserModalClose();
        loadUsers();
      } else {
        toast.error(response.msg || (isEdit ? '更新失败' : '创建失败'));
      }
    } catch (error) {
      toast.error(isEdit ? '更新失败' : '创建失败');
    } finally {
      setUserFormLoading(false);
    }
  };

  // 隧道权限管理操作
  const handleManageTunnels = (user: User) => {
    setCurrentUser(user);
    setTunnelForm({
      tunnelId: null,
      flow: 100,
      num: 10,
      expTime: null,
      flowResetTime: 0,
      speedId: null
    });
    onTunnelModalOpen();
    loadUserTunnels(user.id);
  };

  const handleAssignTunnel = async () => {
    if (!tunnelForm.tunnelId || !tunnelForm.expTime || !currentUser) {
      toast.error('请填写完整信息');
      return;
    }

    setAssignLoading(true);
    try {
      const response = await assignUserTunnel({
        userId: currentUser.id,
        tunnelId: tunnelForm.tunnelId,
        flow: tunnelForm.flow,
        num: tunnelForm.num,
        expTime: tunnelForm.expTime.getTime(),
        flowResetTime: tunnelForm.flowResetTime,
        speedId: tunnelForm.speedId
      });

      if (response.code === 0) {
        toast.success('分配成功');
        setTunnelForm({
          tunnelId: null,
          flow: 100,
          num: 10,
          expTime: null,
          flowResetTime: 0,
          speedId: null
        });
        loadUserTunnels(currentUser.id);
      } else {
        toast.error(response.msg || '分配失败');
      }
    } catch (error) {
      toast.error('分配失败');
    } finally {
      setAssignLoading(false);
    }
  };

  const handleEditTunnel = (userTunnel: UserTunnel) => {
    setEditTunnelForm({
      ...userTunnel,
      expTime: userTunnel.expTime
    });
    onEditTunnelModalOpen();
  };

  const handleUpdateTunnel = async () => {
    if (!editTunnelForm) return;

    setEditTunnelLoading(true);
    try {
      const response = await updateUserTunnel({
        id: editTunnelForm.id,
        flow: editTunnelForm.flow,
        num: editTunnelForm.num,
        expTime: editTunnelForm.expTime,
        flowResetTime: editTunnelForm.flowResetTime,
        speedId: editTunnelForm.speedId
      });

      if (response.code === 0) {
        toast.success('更新成功');
        onEditTunnelModalClose();
        if (currentUser) {
          loadUserTunnels(currentUser.id);
        }
      } else {
        toast.error(response.msg || '更新失败');
      }
    } catch (error) {
      toast.error('更新失败');
    } finally {
      setEditTunnelLoading(false);
    }
  };

  const handleRemoveTunnel = (userTunnel: UserTunnel) => {
    setTunnelToDelete(userTunnel);
    onDeleteTunnelModalOpen();
  };

  const handleConfirmRemoveTunnel = async () => {
    if (!tunnelToDelete) return;

    try {
      const response = await removeUserTunnel({ id: tunnelToDelete.id });
      if (response.code === 0) {
        toast.success('删除成功');
        if (currentUser) {
          loadUserTunnels(currentUser.id);
        }
        onDeleteTunnelModalClose();
        setTunnelToDelete(null);
      } else {
        toast.error(response.msg || '删除失败');
      }
    } catch (error) {
      toast.error('删除失败');
    }
  };

  // 过滤数据
  const availableTunnels = tunnels.filter(
    tunnel => !userTunnels.some(ut => ut.tunnelId === tunnel.id)
  );

  const availableSpeedLimits = speedLimits.filter(
    speedLimit => speedLimit.tunnelId === tunnelForm.tunnelId
  );

  const editAvailableSpeedLimits = speedLimits.filter(
    speedLimit => speedLimit.tunnelId === editTunnelForm?.tunnelId
  );

  return (
    <AdminLayout>
      <div className="px-3 lg:px-6 py-8">
      {/* 页面头部 */}
      <div className="flex flex-col gap-4 mb-6">
        <div className="flex items-center gap-3">
          <UserIcon className="w-6 h-6 text-primary" />
          <h1 className="text-2xl font-bold">用户管理</h1>
        </div>
        
        <div className="flex flex-col sm:flex-row gap-3 items-stretch sm:items-center justify-between">
          <div className="flex items-center gap-3 flex-1 max-w-md">
            <Input
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="搜索用户名"
              startContent={<SearchIcon className="w-4 h-4 text-default-400" />}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              className="flex-1"
              classNames={{
                base: "bg-default-100",
                input: "bg-transparent",
                inputWrapper: "bg-default-100 border-2 border-default-200 hover:border-default-300 focus-within:border-primary data-[hover=true]:border-default-300"
              }}
            />
            <Button
              onClick={handleSearch}
              variant="solid"
              color="primary"
              isIconOnly
              className="min-h-10 w-10"
            >
              <SearchIcon className="w-4 h-4" />
            </Button>
          </div>
          
          <Button
            color="primary"
            startContent={<PlusIcon className="w-4 h-4" />}
            onClick={handleAdd}
            className="min-h-8"
          >
            新增用户
          </Button>
        </div>
      </div>

      {/* 用户列表 */}
      {loading ? (
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <Spinner size="sm" />
            <span className="text-default-600">正在加载...</span>
          </div>
        </div>
      ) : users.length === 0 ? (
        <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
          <CardBody className="text-center py-16">
            <div className="flex flex-col items-center gap-4">
              <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                <UserIcon className="w-8 h-8 text-default-400" />
              </div>
              <div>
                <h3 className="text-lg font-semibold text-foreground">暂无用户数据</h3>
                <p className="text-default-500 text-sm mt-1">还没有创建任何用户，点击上方按钮开始创建</p>
              </div>
            </div>
          </CardBody>
        </Card>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
          {users.map((user) => {
            const userStatus = getUserStatus(user);
            const expStatus = user.expTime ? getExpireStatus(user.expTime) : null;
            const usedFlow = calculateUserTotalUsedFlow(user);
            const flowPercent = user.flow > 0 ? Math.min((usedFlow / (user.flow * 1024 * 1024 * 1024)) * 100, 100) : 0;
            
            return (
              <Card 
                key={user.id} 
                className="shadow-sm border border-divider hover:shadow-md transition-shadow duration-200"
              >
                <CardHeader className="pb-2">
                  <div className="flex justify-between items-start w-full">
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-foreground truncate text-sm">
                        {user.name || user.user}
                      </h3>
                      <p className="text-xs text-default-500 truncate">@{user.user}</p>
                    </div>
                    <div className="flex items-center gap-1.5 ml-2">
                      <Chip 
                        color={userStatus.color} 
                        variant="flat" 
                        size="sm"
                        className="text-xs"
                      >
                        {userStatus.text}
                      </Chip>
                    </div>
                  </div>
                </CardHeader>

                <CardBody className="pt-0 pb-3">
                  <div className="space-y-2">
                    {/* 流量信息 */}
                    <div className="space-y-1.5">
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">流量限制</span>
                        <span className="font-medium text-xs">{formatFlow(user.flow, 'gb')}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">已使用</span>
                        <span className="font-medium text-xs text-danger">{formatFlow(usedFlow)}</span>
                      </div>
                      <Progress 
                        size="sm" 
                        value={flowPercent}
                        color={flowPercent > 90 ? 'danger' : flowPercent > 70 ? 'warning' : 'success'}
                        className="mt-1"
                        aria-label={`流量使用 ${flowPercent.toFixed(1)}%`}
                      />
                    </div>

                    {/* 其他信息 */}
                    <div className="space-y-1.5 pt-2 border-t border-divider">
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">转发数量</span>
                        <span className="font-medium text-xs">{user.num}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-default-600">重置日期</span>
                        <span className="text-xs">{user.flowResetTime === 0 ? '不重置' : `每月${user.flowResetTime}号`}</span>
                      </div>
                      {user.expTime && (
                        <div className="flex justify-between text-sm">
                          <span className="text-default-600">过期时间</span>
                          <div className="text-right">
                            <div className="text-xs">{formatDate(user.expTime)}</div>
                            {expStatus && (
                              <Chip 
                                color={expStatus.color} 
                                variant="flat" 
                                size="sm"
                                className="text-xs mt-0.5"
                              >
                                {expStatus.text}
                              </Chip>
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                  
                  <div className="flex gap-1.5 mt-3">
                    <Button
                      size="sm"
                      variant="flat"
                      color="primary"
                      onPress={() => handleEdit(user)}
                      className="flex-1 min-h-8"
                      startContent={<EditIcon className="w-3 h-3" />}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color="success"
                      onPress={() => handleManageTunnels(user)}
                      className="flex-1 min-h-8"
                      startContent={<SettingsIcon className="w-3 h-3" />}
                    >
                      权限
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color="danger"
                      onPress={() => handleDelete(user)}
                      className="flex-1 min-h-8"
                      startContent={<DeleteIcon className="w-3 h-3" />}
                    >
                      删除
                    </Button>
                  </div>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}

      {/* 分页 */}
      {pagination.total > 0 && (
        <div className="flex justify-center mt-6">
          <Pagination
            total={Math.ceil(pagination.total / pagination.size)}
            page={pagination.current}
            onChange={(page) => setPagination(prev => ({ ...prev, current: page }))}
            showControls
            showShadow
          />
        </div>
      )}

      {/* 用户表单模态框 */}
      <Modal
        isOpen={isUserModalOpen}
        onClose={onUserModalClose}
        size="2xl"
        scrollBehavior="outside"
      >
        <ModalContent>
          <ModalHeader>
            {isEdit ? '编辑用户' : '新增用户'}
          </ModalHeader>
          <ModalBody>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <Input
                label="用户名"
                value={userForm.user}
                onChange={(e) => setUserForm(prev => ({ ...prev, user: e.target.value }))}
                isRequired
              />
              <Input
                label="密码"
                type="password"
                value={userForm.pwd}
                onChange={(e) => setUserForm(prev => ({ ...prev, pwd: e.target.value }))}
                placeholder={isEdit ? '留空则不修改密码' : '请输入密码'}
                isRequired={!isEdit}
              />
              <Input
                label="流量限制(GB)"
                type="number"
                value={userForm.flow.toString()}
                onChange={(e) => {
                  const value = Math.min(Math.max(Number(e.target.value) || 0, 1), 99999);
                  setUserForm(prev => ({ ...prev, flow: value }));
                }}
                min="1"
                max="99999"
                isRequired
              />
              <Input
                label="转发数量"
                type="number"
                value={userForm.num.toString()}
                onChange={(e) => {
                  const value = Math.min(Math.max(Number(e.target.value) || 0, 1), 99999);
                  setUserForm(prev => ({ ...prev, num: value }));
                }}
                min="1"
                max="99999"
                isRequired
              />
              <Select
                label="流量重置日期"
                selectedKeys={[userForm.flowResetTime.toString()]}
                onSelectionChange={(keys) => {
                  const value = Array.from(keys)[0] as string;
                  setUserForm(prev => ({ ...prev, flowResetTime: Number(value) }));
                }}
              >
                <>
                  <SelectItem key="0" textValue="不重置">
                    不重置
                  </SelectItem>
                {Array.from({ length: 31 }, (_, i) => i + 1).map(day => (
                  <SelectItem key={day.toString()} textValue={`每月${day}号（0点重置）`}>
                    每月{day}号（0点重置）
                  </SelectItem>
                ))}
                </>
              </Select>
              <DatePicker
                label="过期时间"
                value={userForm.expTime ? parseDate(userForm.expTime.toISOString().split('T')[0]) : null}
                onChange={(date) => {
                  if (date) {
                    const jsDate = new Date(date.year, date.month - 1, date.day, 23, 59, 59);
                    setUserForm(prev => ({ ...prev, expTime: jsDate }));
                  } else {
                    setUserForm(prev => ({ ...prev, expTime: null }));
                  }
                }}
                isRequired
                showMonthAndYearPickers
                className="cursor-pointer"
              />
            </div>
            
            <RadioGroup
              label="状态"
              value={userForm.status.toString()}
              onValueChange={(value) => setUserForm(prev => ({ ...prev, status: Number(value) }))}
              orientation="horizontal"
            >
              <Radio value="1">正常</Radio>
              <Radio value="0">禁用</Radio>
            </RadioGroup>
          </ModalBody>
          <ModalFooter>
            <Button onPress={onUserModalClose}>
              取消
            </Button>
            <Button
              color="primary"
              onPress={handleSubmitUser}
              isLoading={userFormLoading}
            >
              确定
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 隧道权限管理模态框 */}
      <Modal
        isOpen={isTunnelModalOpen}
        onClose={onTunnelModalClose}
        size="5xl"
        scrollBehavior="outside"
      >
        <ModalContent>
          <ModalHeader>
            用户 {currentUser?.user} 的隧道权限管理
          </ModalHeader>
          <ModalBody>
            <Tabs defaultSelectedKey="assign">
              <Tab key="assign" title="分配新权限">
                <div className="space-y-4">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <Select
                      label="选择隧道"
                      selectedKeys={tunnelForm.tunnelId ? [tunnelForm.tunnelId.toString()] : []}
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0] as string;
                        setTunnelForm(prev => ({ ...prev, tunnelId: Number(value) || null, speedId: null }));
                      }}
                    >
                      {availableTunnels.map(tunnel => (
                        <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                          {tunnel.name}
                        </SelectItem>
                      ))}
                    </Select>
                    
                    <Select
                      label="限速规则"
                      selectedKeys={tunnelForm.speedId ? [tunnelForm.speedId.toString()] : ["null"]}
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0] as string;
                        setTunnelForm(prev => ({ ...prev, speedId: value === "null" ? null : Number(value) }));
                      }}
                      isDisabled={!tunnelForm.tunnelId}
                    >
                      {[
                        <SelectItem key="null" textValue="不限速">不限速</SelectItem>,
                        ...availableSpeedLimits.map(speedLimit => (
                          <SelectItem key={speedLimit.id.toString()} textValue={speedLimit.name}>
                            {speedLimit.name}
                          </SelectItem>
                        ))
                      ]}
                    </Select>
                    
                    <Input
                      label="流量限制(GB)"
                      type="number"
                      value={tunnelForm.flow.toString()}
                      onChange={(e) => {
                        const value = Math.min(Math.max(Number(e.target.value) || 0, 1), 99999);
                        setTunnelForm(prev => ({ ...prev, flow: value }));
                      }}
                      min="1"
                      max="99999"
                    />
                    
                    <Input
                      label="转发数量"
                      type="number"
                      value={tunnelForm.num.toString()}
                      onChange={(e) => {
                        const value = Math.min(Math.max(Number(e.target.value) || 0, 1), 99999);
                        setTunnelForm(prev => ({ ...prev, num: value }));
                      }}
                      min="1"
                      max="99999"
                    />
                    
                    <Select
                      label="流量重置日期"
                      selectedKeys={[tunnelForm.flowResetTime.toString()]}
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0] as string;
                        setTunnelForm(prev => ({ ...prev, flowResetTime: Number(value) }));
                      }}
                    >
                      <>
                        <SelectItem key="0" textValue="不重置">
                          不重置
                        </SelectItem>
                      {Array.from({ length: 31 }, (_, i) => i + 1).map(day => (
                        <SelectItem key={day.toString()} textValue={`每月${day}号（0点重置）`}>
                          每月{day}号（0点重置）
                        </SelectItem>
                      ))}
                      </>
                    </Select>
                    
                    <DatePicker
                      label="到期时间"
                      value={tunnelForm.expTime ? parseDate(tunnelForm.expTime.toISOString().split('T')[0]) : null}
                      onChange={(date) => {
                        if (date) {
                          const jsDate = new Date(date.year, date.month - 1, date.day, 23, 59, 59);
                          setTunnelForm(prev => ({ ...prev, expTime: jsDate }));
                        } else {
                          setTunnelForm(prev => ({ ...prev, expTime: null }));
                        }
                      }}
                      showMonthAndYearPickers
                      className="cursor-pointer"
                    />
                  </div>
                  
                  <Button
                    color="primary"
                    onPress={handleAssignTunnel}
                    isLoading={assignLoading}
                  >
                    分配权限
                  </Button>
                </div>
              </Tab>
              
              <Tab key="list" title="已有权限">
                <Table
                  aria-label="用户隧道权限列表"
                  classNames={{
                    wrapper: "shadow-none",
                    th: "bg-gray-50 text-gray-700 font-medium"
                  }}
                >
                  <TableHeader>
                    <TableColumn>隧道名称</TableColumn>
                    <TableColumn>流量统计</TableColumn>
                    <TableColumn>转发数量</TableColumn>
                    <TableColumn>限速规则</TableColumn>
                    <TableColumn>重置时间</TableColumn>
                    <TableColumn>到期时间</TableColumn>
                    <TableColumn>操作</TableColumn>
                  </TableHeader>
                  <TableBody
                    items={userTunnels}
                    isLoading={tunnelListLoading}
                    loadingContent={<Spinner />}
                    emptyContent="暂无隧道权限"
                  >
                    {(userTunnel) => (
                      <TableRow key={userTunnel.id}>
                        <TableCell>{userTunnel.tunnelName}</TableCell>
                        <TableCell>
                          <div className="flex flex-col gap-1">
                            <div className="flex justify-between text-small">
                              <span className="text-gray-600">限制:</span>
                              <span className="font-medium">{formatFlow(userTunnel.flow, 'gb')}</span>
                            </div>
                            <div className="flex justify-between text-small">
                              <span className="text-gray-600">已用:</span>
                              <span className="font-medium text-danger">
                                {formatFlow(calculateTunnelUsedFlow(userTunnel))}
                              </span>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>{userTunnel.num}</TableCell>
                        <TableCell>
                          <Chip
                            color={userTunnel.speedLimitName ? 'warning' : 'success'}
                            size="sm"
                            variant="flat"
                          >
                            {userTunnel.speedLimitName || '不限速'}
                          </Chip>
                        </TableCell>
                        <TableCell>{userTunnel.flowResetTime === 0 ? '不重置' : `每月${userTunnel.flowResetTime}号`}</TableCell>
                        <TableCell>{formatDate(userTunnel.expTime)}</TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <Button
                              size="sm"
                              variant="flat"
                              color="primary"
                              isIconOnly
                              onClick={() => handleEditTunnel(userTunnel)}
                            >
                              <EditIcon className="w-4 h-4" />
                            </Button>
                            <Button
                              size="sm"
                              variant="flat"
                              color="danger"
                              isIconOnly
                              onClick={() => handleRemoveTunnel(userTunnel)}
                            >
                              <DeleteIcon className="w-4 h-4" />
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              </Tab>
            </Tabs>
          </ModalBody>
          <ModalFooter>
            <Button onPress={onTunnelModalClose}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 编辑隧道权限模态框 */}
      <Modal
        isOpen={isEditTunnelModalOpen}
        onClose={onEditTunnelModalClose}
        size="2xl"
        scrollBehavior="outside"
      >
        <ModalContent>
          <ModalHeader>
            编辑隧道权限 - {editTunnelForm?.tunnelName}
          </ModalHeader>
          <ModalBody>
            {editTunnelForm && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <Input
                  label="流量限制(GB)"
                  type="number"
                  value={editTunnelForm.flow.toString()}
                  onChange={(e) => {
                    const value = Math.min(Math.max(Number(e.target.value) || 0, 1), 99999);
                    setEditTunnelForm(prev => prev ? { ...prev, flow: value } : null);
                  }}
                  min="1"
                  max="99999"
                />
                
                <Input
                  label="转发数量"
                  type="number"
                  value={editTunnelForm.num.toString()}
                  onChange={(e) => {
                    const value = Math.min(Math.max(Number(e.target.value) || 0, 1), 99999);
                    setEditTunnelForm(prev => prev ? { ...prev, num: value } : null);
                  }}
                  min="1"
                  max="99999"
                />
                
                <Select
                  label="限速规则"
                  selectedKeys={editTunnelForm.speedId ? [editTunnelForm.speedId.toString()] : ['null']}
                  onSelectionChange={(keys) => {
                    const value = Array.from(keys)[0] as string;
                    setEditTunnelForm(prev => prev ? { ...prev, speedId: value === 'null' ? null : Number(value) } : null);
                  }}
                >
                  {[
                    <SelectItem key="null" textValue="不限速">不限速</SelectItem>,
                    ...editAvailableSpeedLimits.map(speedLimit => (
                      <SelectItem key={speedLimit.id.toString()} textValue={speedLimit.name}>
                        {speedLimit.name}
                      </SelectItem>
                    ))
                  ]}
                </Select>
                
                <Select
                  label="流量重置日期"
                  selectedKeys={[editTunnelForm.flowResetTime.toString()]}
                  onSelectionChange={(keys) => {
                    const value = Array.from(keys)[0] as string;
                    setEditTunnelForm(prev => prev ? { ...prev, flowResetTime: Number(value) } : null);
                  }}
                >
                  <>
                    <SelectItem key="0" textValue="不重置">
                      不重置
                    </SelectItem>
                  {Array.from({ length: 31 }, (_, i) => i + 1).map(day => (
                    <SelectItem key={day.toString()} textValue={`每月${day}号（0点重置）`}>
                      每月{day}号（0点重置）
                    </SelectItem>
                  ))}
                  </>
                </Select>
                
                <DatePicker
                  label="到期时间"
                  value={editTunnelForm.expTime ? parseDate(new Date(editTunnelForm.expTime).toISOString().split('T')[0]) : null}
                  onChange={(date) => {
                    if (date) {
                      const jsDate = new Date(date.year, date.month - 1, date.day, 23, 59, 59);
                      setEditTunnelForm(prev => prev ? { ...prev, expTime: jsDate.getTime() } : null);
                    } else {
                      setEditTunnelForm(prev => prev ? { ...prev, expTime: Date.now() } : null);
                    }
                  }}
                  showMonthAndYearPickers
                  className="cursor-pointer"
                  isRequired
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button onPress={onEditTunnelModalClose}>
              取消
            </Button>
            <Button
              color="primary"
              onPress={handleUpdateTunnel}
              isLoading={editTunnelLoading}
            >
              确定
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 删除确认对话框 */}
      <Modal
        isOpen={isDeleteModalOpen}
        onClose={onDeleteModalClose}
        size="md"
        scrollBehavior="outside"
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            确认删除用户
          </ModalHeader>
          <ModalBody>
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-danger-100 rounded-full flex items-center justify-center">
                <DeleteIcon className="w-6 h-6 text-danger" />
              </div>
              <div className="flex-1">
                <p className="text-foreground">
                  确定要删除用户 <span className="font-semibold text-danger">"{userToDelete?.user}"</span> 吗？
                </p>
                <p className="text-small text-default-500 mt-1">
                  此操作不可撤销，用户的所有数据将被永久删除。
                </p>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button 
              variant="light" 
              onPress={onDeleteModalClose}
            >
              取消
            </Button>
            <Button 
              color="danger" 
              onPress={handleConfirmDelete}
            >
              确认删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 删除隧道权限确认对话框 */}
      <Modal
        isOpen={isDeleteTunnelModalOpen}
        onClose={onDeleteTunnelModalClose}
        size="md"
        scrollBehavior="outside"
      >
        <ModalContent>
          <ModalHeader className="flex flex-col gap-1">
            确认删除隧道权限
          </ModalHeader>
          <ModalBody>
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-danger-100 rounded-full flex items-center justify-center">
                <DeleteIcon className="w-6 h-6 text-danger" />
              </div>
              <div className="flex-1">
                <p className="text-foreground">
                  确定要删除用户 <span className="font-semibold">{currentUser?.user}</span> 对隧道 <span className="font-semibold text-danger">"{tunnelToDelete?.tunnelName}"</span> 的权限吗？
                </p>
                <p className="text-small text-default-500 mt-1">
                  删除后该用户将无法使用此隧道创建转发，此操作不可撤销。
                </p>
              </div>
            </div>
          </ModalBody>
          <ModalFooter>
            <Button 
              variant="light" 
              onPress={onDeleteTunnelModalClose}
            >
              取消
            </Button>
            <Button 
              color="danger" 
              onPress={handleConfirmRemoveTunnel}
            >
              确认删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      </div>
    </AdminLayout>
  );
} 