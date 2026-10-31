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

public class AccountManager {

    private static final Logger logger = LoggerFactory.getLogger(AccountManager.class);

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

    public static Path getAccountsPath() {
        return ACCOUNTS_PATH;
    }

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

            
            logger.error("加载账号失败: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(ACCOUNTS_PATH.getParent());

            AccountsData data = new AccountsData();
            data.accounts = new ArrayList<>(accounts.values());
            data.currentAccountId = currentAccountId;

            String json = gson.toJson(data);

            com.mclauncher.utils.FileUtils.writeAtomic(ACCOUNTS_PATH, json);
            restrictPermissions(ACCOUNTS_PATH);
            logger.debug("账号列表已保存: {}", ACCOUNTS_PATH);
        } catch (Exception e) {
            logger.error("保存账号失败: {} ({})", e.getMessage(), ACCOUNTS_PATH);
        }
    }

    public void addAccount(Account account) {
        accounts.put(account.getId(), account);
        if (currentAccountId == null) {
            currentAccountId = account.getId();
        }
        save();
        logger.info("添加账号: {}", account.getUsername());
    }

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

    public void updateAccount(Account account) {
        accounts.put(account.getId(), account);
        save();
    }

    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }

    public List<Account> getAllAccounts() {
        return new ArrayList<>(accounts.values());
    }

    public Account getCurrentAccount() {
        if (currentAccountId == null) {
            return null;
        }
        return accounts.get(currentAccountId);
    }

    public void setCurrentAccount(String accountId) {
        if (accounts.containsKey(accountId)) {
            this.currentAccountId = accountId;
            save();
            logger.info("切换账号: {}", accounts.get(accountId).getUsername());
        }
    }

    public String getCurrentAccountId() {
        return currentAccountId;
    }

    public Account findByName(String username) {
        for (Account account : accounts.values()) {
            if (account.getUsername().equalsIgnoreCase(username)) {
                return account;
            }
        }
        return null;
    }

    private static class AccountsData {
        List<Account> accounts;
        String currentAccountId;
    }
}
