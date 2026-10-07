# ---- ビルド ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY lib ./lib
COPY src ./src

# 中身が空のファイルを除いてコンパイルする
RUN mkdir -p bin && \
    javac -encoding UTF-8 -d bin -cp lib/h2-2.2.224.jar \
      $(find src -name "*.java" -size +0)

# ---- 実行 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# 日本語のログとタイムスタンプのために
ENV TZ=Asia/Tokyo
ENV LANG=C.UTF-8
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

COPY --from=build /build/bin ./bin
COPY lib ./lib
COPY sql ./sql
COPY web ./web

# data/ は compose 側で bind mount する
VOLUME ["/app/data"]

EXPOSE 8080

# root で動かさない
RUN chown -R 1000:1000 /app
USER 1000:1000

CMD ["java", "-Xmx256m", "-cp", "bin:lib/h2-2.2.224.jar", "com.pereperia.Main", "web"]
