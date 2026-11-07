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
     * 必须与 config.json 放在同一位置（启动器程序目录下的 .mc_launcher），
     */
    private static final Path ACCOUNTS_PATH =
            ConfigManager.getConfigDir().resolve("accounts.json");

    private final Map<String, Account> accounts;
    private final Gson gson;
    private volatile String currentAccountId;
    /** 账号文件的读写锁。 */
    private final Object writeLock = new Object();
    /** 每个账号一把刷新锁：防止并发刷新把轮换后的 refresh_token 写乱 */
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

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
        synchronized (writeLock) {
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
    }

    /** 确保账号的 Minecraft 令牌可用；过期时用 refresh_token 自动刷新。 */
    public Account ensureFresh(Account account) {
        if (account == null || account.getType() != Account.Type.MICROSOFT
                || !account.isTokenExpired()) {
            return account;
        }
        Object lock = refreshLocks.computeIfAbsent(account.getId(), k -> new Object());
        synchronized (lock) {
            Account cur = accounts.get(account.getId());
            if (cur == null) {
                cur = account;
            }
            if (!cur.isTokenExpired()) {
                return cur;   // 另一个线程刚刷新过
            }
            try {
                Account fresh = cur.refresh();
                accounts.put(fresh.getId(), fresh);
                save();
                logger.info("账号 {} 的令牌已自动刷新", fresh.getUsername());
                return fresh;
            } catch (Exception e) {
                logger.warn("刷新账号 {} 失败（需要重新登录）: {}", cur.getUsername(), e.getMessage());
                return cur;
            }
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

    /** 尽量限制账号文件权限（仅当前用户可读写）。 */
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