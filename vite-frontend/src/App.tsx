import { Route, Routes } from "react-router-dom";
import { useEffect } from "react";

import IndexPage from "@/pages/index";
import ChangePasswordPage from "@/pages/change-password";
import DashboardPage from "@/pages/dashboard";
import ForwardPage from "@/pages/forward";
import TunnelPage from "@/pages/tunnel";
import NodePage from "@/pages/node";
import UserPage from "@/pages/user";
import LimitPage from "@/pages/limit";
import ConfigPage from "@/pages/config";

import { isLoggedIn } from "@/utils/auth";
import { siteConfig } from "@/config/site";

// 简化的路由保护组件 - 使用浏览器重定向避免React循环
const ProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const authenticated = isLoggedIn();
  
  useEffect(() => {
    if (!authenticated) {
      // 使用浏览器原生重定向，避免React渲染循环
      window.location.replace('/');
    }
  }, [authenticated]);

  if (!authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-lg">重定向中...</div>
      </div>
    );
  }

  return <>{children}</>;
};


// 登录页面路由组件 - 已登录则重定向到dashboard
const LoginRoute = () => {
  const authenticated = isLoggedIn();
  
  useEffect(() => {
    if (authenticated) {
      // 使用浏览器原生重定向
      window.location.replace('/dashboard');
    }
  }, [authenticated]);
  
  if (authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-lg">重定向中...</div>
      </div>
    );
  }
  
  return <IndexPage />;
};

function App() {
  // 立即设置页面标题（使用已从缓存读取的配置）
  useEffect(() => {
    document.title = siteConfig.name;
    
    // 异步检查是否有配置更新
    const checkTitleUpdate = async () => {
      try {
        // 引入必要的函数
        const { getCachedConfig } = await import('@/config/site');
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName && cachedAppName !== document.title) {
          document.title = cachedAppName;
        }
      } catch (error) {
        console.warn('检查标题更新失败:', error);
      }
    };

    // 延迟检查，避免阻塞初始渲染
    const timer = setTimeout(checkTitleUpdate, 100);

    return () => clearTimeout(timer);
  }, []);

  return (
    <Routes>
      <Route path="/" element={<LoginRoute />} />
      <Route 
        path="/change-password" 
        element={
          <ProtectedRoute>
            <ChangePasswordPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/dashboard" 
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/forward" 
        element={
          <ProtectedRoute>
            <ForwardPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/tunnel" 
        element={
          <ProtectedRoute>
            <TunnelPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/node" 
        element={
          <ProtectedRoute>
            <NodePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/user" 
        element={
          <ProtectedRoute>
            <UserPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/limit" 
        element={
          <ProtectedRoute>
            <LimitPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/config" 
        element={
          <ProtectedRoute>
            <ConfigPage />
          </ProtectedRoute>
        } 
      />
    </Routes>
  );
}

export default App;
