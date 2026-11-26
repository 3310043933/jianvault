package cn.local.simpleauth;

import java.util.HashMap;
import java.util.Map;

/** Translates known validation errors only. Never apply to user-saved values. */
final class ErrorMessages {
    private ErrorMessages() {}
    private static final Map<String, String> ENGLISH = new HashMap<>();
    static {
        ENGLISH.put("保险库版本不受支持", "This vault version is not supported.");
        ENGLISH.put("保险库包含无效的附加数据", "The vault contains invalid trailing data.");
        ENGLISH.put("保险库格式无效", "The vault format is invalid.");
        ENGLISH.put("备份版本不受支持", "This backup version is not supported.");
        ENGLISH.put("备份含未知分组", "The backup references an unknown group.");
        ENGLISH.put("备份加密参数不受支持", "The backup encryption parameters are not supported.");
        ENGLISH.put("备份内容过大", "The backup is too large.");
        ENGLISH.put("备份文本不完整或格式损坏", "The backup text is incomplete or malformed.");
        ENGLISH.put("备份文本为空或超过 12 MB 上限", "The backup text is missing or exceeds the 12 MB limit.");
        ENGLISH.put("备份长度无效", "The backup length is invalid.");
        ENGLISH.put("备注最多 32768 字符", "Notes must not exceed 32768 characters.");
        ENGLISH.put("不是简验证支持的加密备份文本", "This is not a supported JianVault encrypted backup.");
        ENGLISH.put("不支持此验证码算法", "This authenticator algorithm is not supported.");
        ENGLISH.put("此名称由系统使用，请换一个分组名称", "This group name is reserved. Choose another name.");
        ENGLISH.put("此条目尚未添加验证码", "This entry does not contain an authenticator key.");
        ENGLISH.put("登录信息不可为空", "Login details are required.");
        ENGLISH.put("登录信息不能为 null", "Login details cannot be null.");
        ENGLISH.put("登录账号最多 200 字符", "The username must not exceed 200 characters.");
        ENGLISH.put("二维码包含重复参数", "The QR code contains duplicate parameters.");
        ENGLISH.put("二维码的位数或周期无效", "The QR code contains an invalid digit count or refresh period.");
        ENGLISH.put("二维码格式无效", "The QR code format is invalid.");
        ENGLISH.put("二维码内容过长", "The QR code content is too long.");
        ENGLISH.put("二维码缺少密钥", "The QR code does not contain a setup key.");
        ENGLISH.put("二维码中的服务名称不一致", "The service names in the QR code do not match.");
        ENGLISH.put("分组编号无效", "The group identifier is invalid.");
        ENGLISH.put("分组不存在", "The group does not exist.");
        ENGLISH.put("分组名称或编号重复", "A group name or identifier is duplicated.");
        ENGLISH.put("分组名称需为 1～40 个字符", "The group name must contain 1–40 characters.");
        ENGLISH.put("合并后分组超过 1000 个，无法合并；本机数据未修改", "Merging would exceed 1000 groups. Nothing on this device was changed.");
        ENGLISH.put("恢复码不存在", "The recovery code does not exist.");
        ENGLISH.put("恢复码列表含空项或重复项", "The recovery-code list contains empty or duplicate entries.");
        ENGLISH.put("加密密钥不可用，原数据未被覆盖", "The encryption key is unavailable. The original data was preserved.");
        ENGLISH.put("仅支持 6 或 8 位验证码", "Only 6-digit and 8-digit codes are supported.");
        ENGLISH.put("每条恢复码需为 4～128 位字母、数字、连字符或下划线，请只粘贴恢复码", "Each recovery code must contain 4–128 letters, digits, hyphens, or underscores. Paste codes only.");
        ENGLISH.put("密码条目不应包含验证码密钥", "A password-only entry must not contain an authenticator key.");
        ENGLISH.put("密码最多 16384 字符", "The password must not exceed 16384 characters.");
        ENGLISH.put("密钥必须是 Base32：字母 A–Z 和数字 2–7", "The setup key must use Base32: letters A–Z and digits 2–7.");
        ENGLISH.put("密钥长度不正确，需 10～128 字节", "The authenticator key must contain 10–128 bytes.");
        ENGLISH.put("请扫描 TOTP 验证器二维码，不支持 HOTP 或普通网址", "Scan a TOTP authenticator QR code. HOTP codes and ordinary website URLs are not supported.");
        ENGLISH.put("请填写登录账号和密码", "Enter a username and password.");
        ENGLISH.put("请填写网站或条目名称", "Enter a website or entry name.");
        ENGLISH.put("请填写网站名称", "Enter a website name.");
        ENGLISH.put("请填写账号名称（最多 200 字）", "Enter an account name of up to 200 characters.");
        ENGLISH.put("请至少粘贴一条恢复码", "Paste at least one recovery code.");
        ENGLISH.put("设备不支持此加密算法", "The device does not support this cryptographic algorithm.");
        ENGLISH.put("设备时间不正确", "The device time is incorrect.");
        ENGLISH.put("数据超过备份大小上限（8 MB），未导出任何内容", "The data exceeds the 8 MB backup limit. Nothing was exported.");
        ENGLISH.put("刷新周期需在 1～300 秒之间", "The refresh period must be between 1 and 300 seconds.");
        ENGLISH.put("条目所属分组不存在", "The entry references a group that does not exist.");
        ENGLISH.put("网站地址最多 2048 字符", "The website URL must not exceed 2048 characters.");
        ENGLISH.put("无法安全处理分组冲突，本机数据未修改", "The group conflict could not be resolved safely. Local data was not changed.");
        ENGLISH.put("无法识别的账号类型", "The account type is not recognized.");
        ENGLISH.put("粘贴内容过长，最多 200 条恢复码", "The pasted text is too long. Up to 200 recovery codes are supported.");
        ENGLISH.put("账号文件损坏，原数据未被覆盖", "The account file is damaged. The original data was preserved.");
        ENGLISH.put("最多保存 200 条恢复码", "You can save up to 200 recovery codes per entry.");
        ENGLISH.put("最多创建 1000 个分组", "You can create up to 1000 groups.");
        ENGLISH.put("Base32 密钥末尾不正确", "The Base32 key has invalid trailing bits.");
        ENGLISH.put("Base32 密钥长度或填充错误", "The Base32 key length or padding is invalid.");
        ENGLISH.put("Key 不能全部为空格", "The key cannot consist only of whitespace.");
        ENGLISH.put("Key 需为 12～1024 个字符，建议使用较长的随机口令", "Use a key of 12–1024 characters, preferably a long random passphrase.");
    }
    static String translate(String message, boolean english) {
        return english ? ENGLISH.getOrDefault(message, message) : message;
    }
}
