package com.pereperia.dao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DbManager {

    private static final String URL_PREFIX = "jdbc:h2:./data/";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static String dbName = "kurohon";

    /** 使用する教材を切り替える */
    public static void use(String name) {
        dbName = name;
    }

    /** 現在の教材名を返す */
    public static String currentBook() {
        return dbName;
    }

    /** 接続を返す。閉じる責任は呼び出し側にある */
    public static Connection getConnection() throws SQLException {
        // AUTO_SERVER=TRUE で、Web・CLI・レポートが同じ DB を同時に開ける
        return DriverManager.getConnection(
                URL_PREFIX + dbName + ";AUTO_SERVER=TRUE", USER, PASSWORD);
    }

    /** schema.sql を読み込んでテーブルを作成する */
    public static void initialize(){
        try (Connection conn = getConnection();
        Statement stmt = conn.createStatement()){

            String script = Files.readString(Path.of("sql/schema.sql"));

            for (String sql : script.split(";")){
                if (sql.isBlank()){
                    continue;
                }
                stmt.execute(sql);
            }
            System.out.println("テーブルを初期化しました");

        } catch (IOException e) {
            System.err.println("schema.sql が読めません： " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("DB初期化に失敗しました：" + e.getMessage());
        }
    }
}