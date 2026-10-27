FROM eclipse-temurin:17-jdk-jammy AS builder

# El proyecto Maven vive en market/ (contexto de construcción = raíz del repositorio)
WORKDIR /app/market

# Wrapper y pom primero (capa de dependencias cacheable)
COPY market/mvnw market/pom.xml ./
COPY market/.mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline -q

# Código fuente y empaquetado
COPY market/src ./src
RUN ./mvnw -B -ntp package -DskipTests -q

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Instalar utilidades básicas
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Crear usuario no root para ejecutar la aplicación
RUN groupadd -r nexus && useradd -r -g nexus -s /bin/false nexus

# Copiar el JAR construido
COPY --from=builder /app/market/target/*.jar app.jar

# Cambiar propietario y cambiar al usuario no root
RUN chown -R nexus:nexus /app
USER nexus

# Puerto expuesto
EXPOSE 8080

# Variables de entorno NO sensibles con valores por defecto. Las credenciales de bases de
# datos y el secreto JWT NO tienen valor por defecto: deben inyectarse al ejecutar el
# contenedor (SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD,
# SPRING_MONGODB_URI y JWT_SECRET); sin ellas la aplicación no arranca.
ENV SPRING_PROFILES_ACTIVE=jpa,prod \
    JWT_ISSUER=nexusmarket \
    JWT_EXPIRATION=86400000 \
    SERVER_PORT=8080 \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never

ENTRYPOINT ["java", "-jar", "/app/app.jar"]