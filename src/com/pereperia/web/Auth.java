package com.pereperia.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * ログイン認証。外部ライブラリは使わない。
 *
 * パスワードは PBKDF2-HMAC-SHA256 で検証する。ハッシュは環境変数で渡す。
 *
 *   KUROHON_USER           ログイン名（既定 "me"）
 *   KUROHON_PASSWORD_HASH  "iterations$saltBase64$hashBase64"
 *   KUROHON_SESSION_DAYS   ログインを保持する日数（既定 30）
 *
 * KUROHON_PASSWORD_HASH が未設定なら認証そのものを無効にする。
 * Tailscale の中だけで使う間は、設定しなければ今までどおり動く。
 *
 * ハッシュの作り方:
 *   java -cp bin com.pereperia.web.Auth 好きなパスワード
 */
public class Auth {

    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** token -> 期限（epoch ミリ秒） */
    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();

    /** 送信元 -> {失敗回数, 集計を始めた時刻} */
    private static final Map<String, long[]> FAILURES = new ConcurrentHashMap<>();

    private static final int MAX_FAILURES = 10;
    private static final long FAILURE_WINDOW_MS = 5 * 60 * 1000L;

    public static final String COOKIE = "kurohon_session";

    private Auth() {
    }

    /** 認証を有効にするか。ハッシュが無ければ無効 */
    public static boolean enabled() {
        String hash = System.getenv("KUROHON_PASSWORD_HASH");
        return hash != null && !hash.isBlank();
    }

    public static String userName() {
        String user = System.getenv("KUROHON_USER");
        return (user == null || user.isBlank()) ? "me" : user;
    }

    private static long sessionMillis() {
        String days = System.getenv("KUROHON_SESSION_DAYS");
        long d = 30;
        try {
            if (days != null && !days.isBlank()) {
                d = Long.parseLong(days.trim());
            }
        } catch (NumberFormatException ignored) {
            // 既定の 30 日を使う
        }
        return d * 24 * 60 * 60 * 1000L;
    }

    // ------------------------------------------------------------------
    //  ログイン
    // ------------------------------------------------------------------

    /** 試行回数が上限を超えていないか */
    public static boolean allowAttempt(String from) {
        long[] state = FAILURES.get(from);
        if (state == null) {
            return true;
        }
        if (System.currentTimeMillis() - state[1] > FAILURE_WINDOW_MS) {
            FAILURES.remove(from);
            return true;
        }
        return state[0] < MAX_FAILURES;
    }

    /**
     * 名前とパスワードを照合する。合っていればセッショントークンを返す。
     * 合わなければ null。
     */
    public static String login(String user, String password, String from) {
        if (!enabled()) {
            return null;
        }

        boolean ok = constantTimeEquals(userName(), user == null ? "" : user)
                && verify(password == null ? "" : password,
                          System.getenv("KUROHON_PASSWORD_HASH"));

        if (!ok) {
            long now = System.currentTimeMillis();
            FAILURES.compute(from, (k, v) -> {
                if (v == null || now - v[1] > FAILURE_WINDOW_MS) {
                    return new long[] {1, now};
                }
                v[0]++;
                return v;
            });
            return null;
        }

        FAILURES.remove(from);

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        sweep();
        SESSIONS.put(token, System.currentTimeMillis() + sessionMillis());
        return token;
    }

    /** トークンが生きているか */
    public static boolean valid(String token) {
        if (token == null) {
            return false;
        }
        Long expiry = SESSIONS.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            SESSIONS.remove(token);
            return false;
        }
        return true;
    }

    public static void logout(String token) {
        if (token != null) {
            SESSIONS.remove(token);
        }
    }

    private static void sweep() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(e -> e.getValue() < now);
    }

    // ------------------------------------------------------------------
    //  パスワードのハッシュ
    // ------------------------------------------------------------------

    /** "iterations$salt$hash" を作る */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = pbkdf2(password, salt, ITERATIONS);

        return ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    /** 保存されたハッシュと照合する */
    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.trim().split("\\$");
            if (parts.length != 3) {
                System.err.println("KUROHON_PASSWORD_HASH の形式が違います");
                return false;
            }

            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);

            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));

        } catch (RuntimeException e) {
            System.err.println("パスワードの照合に失敗: " + e.getMessage());
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("ハッシュの計算に失敗しました", e);
        }
    }

    /** 文字列の比較を、長さの違いで早期に抜けないようにする */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    //  ハッシュ生成用のエントリーポイント
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("使い方: java -cp bin com.pereperia.web.Auth <パスワード>");
            return;
        }

        System.out.println();
        System.out.println("KUROHON_PASSWORD_HASH=" + hash(args[0]));
        System.out.println();
        System.out.println("これを .env に書いてください。パスワードそのものは保存されません。");
    }
}
