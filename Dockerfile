FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY .mvn/ .mvn/
COPY contracts/ contracts/
COPY application/ application/
COPY server/ server/
COPY desktop/pom.xml desktop/pom.xml
RUN mvn -B -pl server -am package org.apache.maven.plugins:maven-dependency-plugin:3.7.0:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/output/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /output/lib/ /app/lib/
COPY --from=build /src/server/target/server-0.1.0-SNAPSHOT.jar /app/server.jar
COPY deployment/certs/supabase-ca.crt /app/certs/supabase-ca.crt
ENV SMART_VRMS_DB_CA_PATH=/app/certs/supabase-ca.crt
ENV SMART_VRMS_BEHIND_TLS_PROXY=true
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Duser.timezone=Asia/Kolkata -XX:MaxRAMPercentage=65.0"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-cp","/app/server.jar:/app/lib/*","in.smartvrms.server.ServerMain"]
