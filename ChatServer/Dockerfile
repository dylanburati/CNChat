FROM openjdk:8-jre

ENV APPLICATION_USER cnchat

RUN apt-get update && apt-get install -y netcat default-mysql-client
RUN useradd $APPLICATION_USER

RUN mkdir /app
RUN mkdir -p /var/lib/cnchat/data
RUN chown -R $APPLICATION_USER /app /var/lib/cnchat

USER $APPLICATION_USER

COPY ./bin/ChatServer.jar /app/ChatServer.jar
COPY ./bin/wait-for-mysql.sh /app/wait-for-mysql.sh
WORKDIR /app

ENV JDK_JAVA_OPTIONS -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication
CMD ["java", "-server", "-jar", "ChatServer.jar"]
