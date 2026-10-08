#!/usr/bin/env bash
# 見本データ入りでアプリを起動する（動作確認・スクリーンショット用）
#
#   scripts/demo.sh
#
# ブラウザで http://localhost:8080 を開き、
# ユーザー名 demo / パスワード demo でログインします。
set -eu

cd "$(dirname "$0")/.."
CP="bin:lib/h2-2.2.224.jar"

echo "== ビルド"
rm -rf bin && mkdir -p bin data
javac -encoding UTF-8 -d bin -cp lib/h2-2.2.224.jar $(find src -name "*.java")

if [ ! -f data/kurohon.mv.db ]; then
  echo "== テーブル作成と見本データの投入"
  # CLI を「0: 終了」で起動すると、テーブル作成と問題の登録だけ行って終わる
  echo 0 | java -cp "$CP" com.pereperia.Main > /dev/null
  java -Dfile.encoding=UTF-8 -cp "$CP" org.h2.tools.RunScript \
    -url "jdbc:h2:./data/kurohon;AUTO_SERVER=TRUE" -user sa -password "" \
    -script sql/demo.sql
else
  echo "== data/kurohon.mv.db があるため、見本データの投入は省略します"
fi

echo "== ログインを有効化（demo / demo）"
export KUROHON_USER=demo
KUROHON_PASSWORD_HASH="$(java -cp "$CP" com.pereperia.web.Auth demo | sed -n 's/^KUROHON_PASSWORD_HASH=//p')"
export KUROHON_PASSWORD_HASH

echo "== 起動 → http://localhost:8080"
exec java -Dfile.encoding=UTF-8 -cp "$CP" com.pereperia.Main web
