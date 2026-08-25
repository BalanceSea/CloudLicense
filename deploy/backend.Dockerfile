FROM eclipse-temurin:21-jdk-jammy AS native-build

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY native-obfuscator ./native-obfuscator
RUN cmake -S native-obfuscator -B native-obfuscator/build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build native-obfuscator/build --config Release --parallel

FROM eclipse-temurin:21-jdk-jammy AS java-build

WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY sdk/pom.xml sdk/pom.xml
RUN chmod +x mvnw \
    && ./mvnw -B -pl backend -am dependency:go-offline

COPY backend/src backend/src
RUN ./mvnw -B -pl backend -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 cloudlicense \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin cloudlicense \
    && install -d -o cloudlicense -g cloudlicense /app/data /app/storage /app/native

WORKDIR /app
COPY --from=java-build --chown=cloudlicense:cloudlicense /build/backend/target/cloudlicense-backend-1.0.0-SNAPSHOT.jar /app/cloudlicense.jar
COPY --from=native-build --chown=cloudlicense:cloudlicense /build/native-obfuscator/build/libcloudlicense_obfuscator.so /app/native/libcloudlicense_obfuscator.so

USER cloudlicense
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/app/cloudlicense.jar"]
