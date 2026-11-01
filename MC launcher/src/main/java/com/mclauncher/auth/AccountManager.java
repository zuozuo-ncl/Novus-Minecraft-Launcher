package com.mclauncher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mclauncher.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号管理器
 * 负责账号的保存、加载、切换
 */
public class AccountManager {

    private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);

    /**
     * 账号文件路径
     *
     * 必须与 config.json 放在同一位置（启动器程序目录下的 .mc_launcher），
     * 且是**绝对路径**。
     *
     * 历史问题：这里曾是相对路径 ".mc_launcher/accounts.json"，
     * 依赖进程工作目录。用户以不同的「起始位置」启动时，
     * 读到的就是另一个文件 —— 表现为「重启后账号消失」，
     * 而且会把账号文件散落到随机目录（比如某个游戏目录）里。
     */
    private static final Path ACCOUNTS_PATH =
            ConfigManager.getConfigDir().resolve("accounts.json");

    private final Map<String, Account> accounts;
    private final Gson gson;
    private String currentAccountId;

    public AccountManager() {
        this.accounts = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    /**
     * 获取账号文件位置（供诊断/调试）
     */
    public static Path getAccountsPath() {
        return ACCOUNTS_PATH;
    }

    /**
     * 加载账号列表
     */
    public void load() {
        try {
            if (Files.exists(ACCOUNTS_PATH)) {
                String json = Files.readString(ACCOUNTS_PATH, StandardCharsets.UTF_8);
                Type type = new TypeToken<AccountsData>() {}.getType();
                AccountsData data = gson.fromJson(json, type);

                if (data != null) {
                    if (data.accounts != null) {
                        for (Account account : data.accounts) {
                            if (account != null && account.getId() != null) {
                                accounts.put(account.getId(), account);
                            }
                        }
                    }
                    this.currentAccountId = data.currentAccountId;
                }
                logger.info("加载了 {} 个账号（{}）", accounts.size(), ACCOUNTS_PATH);
            }
        } catch (Exception e) {
            // 捕获所有异常：Gson 解析失败是 RuntimeException，
            // 若漏掉会导致启动崩溃
            logger.error("加载账号失败: {}", e.getMessage());
        }
    }

    /**
     * 保存账号列表
     */
    public void save() {
        try {
            Files.createDirectories(ACCOUNTS_PATH.getParent());

            AccountsData data = new AccountsData();
            data.accounts = new ArrayList<>(accounts.values());
            data.currentAccountId = currentAccountId;

            String json = gson.toJson(data);
            // 原子写：账号文件里有 refresh_token，截断即等于所有账号掉线
            com.mclauncher.utils.FileUtils.writeAtomic(ACCOUNTS_PATH, json);
            restrictPermissions(ACCOUNTS_PATH);
            logger.debug("账号列表已保存: {}", ACCOUNTS_PATH);
        } catch (Exception e) {
            logger.error("保存账号失败: {} ({})", e.getMessage(), ACCOUNTS_PATH);
        }
    }

    /**
     * 添加账号
     */
    public void addAccount(Account account) {
        accounts.put(account.getId(), account);
        if (currentAccountId == null) {
            currentAccountId = account.getId();
        }
        save();
        logger.info("添加账号: {}", account.getUsername());
    }

    /**
     * 尽量限制账号文件权限（仅当前用户可读写）。
     *
     * 文件里是 refresh_token，等同于账号密码。Windows 上用 ACL 收紧，
     * 其他平台改成 600；做不到时只记日志，不影响启动。
     */
    private static void restrictPermissions(Path path) {
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("icacls", path.toString(), "/inheritance:r",
                        "/grant:r", System.getProperty("user.name") + ":F")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } else {
                Files.setPosixFilePermissions(path,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception e) {
            logger.debug("收紧账号文件权限失败: {}", e.getMessage());
        }
    }

    /**
     * 移除账号
     */
    public void removeAccount(String accountId) {
        Account removed = accounts.remove(accountId);
        if (removed != null) {
            logger.info("移除账号: {}", removed.getUsername());
        }
        if (accountId.equals(currentAccountId)) {
            currentAccountId = accounts.isEmpty() ? null : accounts.keySet().iterator().next();
        }
        save();
    }

    /**
     * 更新账号
     */
    public void updateAccount(Account account) {
        accounts.put(account.getId(), account);
        save();
    }

    /**
     * 获取账号
     */
    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }

    /**
     * 获取所有账号
     */
    public List<Account> getAllAccounts() {
        return new ArrayList<>(accounts.values());
    }

    /**
     * 获取当前账号
     */
    public Account getCurrentAccount() {
        if (currentAccountId == null) {
            return null;
        }
        return accounts.get(currentAccountId);
    }

    /**
     * 切换当前账号
     */
    public void setCurrentAccount(String accountId) {
        if (accounts.containsKey(accountId)) {
            this.currentAccountId = accountId;
            save();
            logger.info("切换账号: {}", accounts.get(accountId).getUsername());
        }
    }

    /**
     * 获取当前账号 ID
     */
    public String getCurrentAccountId() {
        return currentAccountId;
    }

    /**
     * 查找同名账号
     */
    public Account findByName(String username) {
        for (Account account : accounts.values()) {
            if (account.getUsername().equalsIgnoreCase(username)) {
                return account;
            }
        }
        return null;
    }

    /**
     * 账号数据包装类
     */
    private static class AccountsData {
        List<Account> accounts;
        String currentAccountId;
    }
}