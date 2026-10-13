FROM public-cn-beijing.cr.volces.com/public/maven:3-jdk-8-slim AS build

# 指定构建过程中的工作目录
WORKDIR /opt/application
COPY pom.xml settings.xml ./
COPY src ./src

# 拉取公网依赖较慢，容易失败，建议在settings.xml中更换依赖源
RUN mvn -B -s /opt/application/settings.xml -f /opt/application/pom.xml clean package

# 运行镜像仅携带正式 JAR 与启动脚本，不携带本机演练源码或历史数据。
FROM public-cn-beijing.cr.volces.com/public/maven:3-jdk-8-slim
WORKDIR /opt/application
COPY --from=build /opt/application/target/douyincloud-demo-0.0.1-SNAPSHOT.jar ./target/douyincloud-demo-0.0.1-SNAPSHOT.jar
COPY run.sh ./run.sh
RUN chmod 755 /opt/application/run.sh

EXPOSE 8000

CMD ["sh","/opt/application/run.sh"]


