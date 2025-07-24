import { useState, useEffect } from "react";
import { Link } from "@heroui/link";
import {
  Navbar as HeroUINavbar,
  NavbarBrand,
  NavbarContent,
} from "@heroui/navbar";

import { ThemeSwitch } from "@/components/theme-switch";
import { Logo } from "@/components/icons";
import { siteConfig, getCachedConfig } from "@/config/site";

export const Navbar = () => {
  // 初始状态使用siteConfig中已经从缓存读取的值，避免闪烁
  const [appName, setAppName] = useState(siteConfig.name);

  useEffect(() => {
    // 异步检查是否有更新的配置
    const checkForUpdates = async () => {
      try {
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName && cachedAppName !== appName) {
          setAppName(cachedAppName);
          // 同步更新siteConfig
          siteConfig.name = cachedAppName;
        }
      } catch (error) {
        console.warn('检查配置更新失败:', error);
      }
    };

    // 延迟执行，避免阻塞初始渲染
    const timer = setTimeout(checkForUpdates, 100);

    // 监听配置更新事件
    const handleConfigUpdate = async () => {
      try {
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName) {
          setAppName(cachedAppName);
          siteConfig.name = cachedAppName;
        }
      } catch (error) {
        console.warn('更新配置失败:', error);
      }
    };

    window.addEventListener('configUpdated', handleConfigUpdate);
    
    return () => {
      clearTimeout(timer);
      window.removeEventListener('configUpdated', handleConfigUpdate);
    };
  }, [appName]);

  return (
    <HeroUINavbar maxWidth="xl" position="sticky" height="60px" className="shrink-0">
      <NavbarContent className="basis-1/5 sm:basis-full" justify="start">
        <NavbarBrand className="gap-2 max-w-fit">
          <Link
            className="flex justify-start items-center gap-2 max-w-[200px] sm:max-w-none"
            color="foreground"
            href="/"
          >
            <Logo size={24} />
            <p className="font-bold text-inherit truncate">{appName}</p>
          </Link>
        </NavbarBrand>
      </NavbarContent>

      <NavbarContent className="basis-1/5 sm:basis-full" justify="end">
        <ThemeSwitch />
      </NavbarContent>
    </HeroUINavbar>
  );
};
