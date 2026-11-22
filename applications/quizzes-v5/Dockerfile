# http://whitfin.io/speeding-up-maven-docker-builds/

FROM maven:3.9.7-eclipse-temurin-21 AS base

COPY ./simulator/pom.xml ./simulator/pom.xml
COPY ./simulator/src ./simulator/src
RUN mvn -f ./simulator/pom.xml clean install -DskipTests

COPY ./applications/quizzes/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B
COPY ./applications/quizzes/src ./src

# The Execute phase's test gate: `docker build --target test`. The work must happen in a RUN — a CMD
# is only image metadata and never executes during a build, so the gate used to pass on code that
# did not compile. The suite is named test-sagas and there is no bare "test" profile, so an
# unqualified -Ptest silently activates nothing.
FROM base AS test
ARG PROFILE=sagas
RUN mvn clean -Ptest-$PROFILE test

FROM base AS run
ARG PROFILE=sagas,stream
RUN mvn clean -P$PROFILE,kubernetes -Dmaven.test.skip=true package

# The runtime image. Builders take this stage by being last — neither the Execute
# phase nor docker compose passes --target here, so the name is documentation only.
FROM eclipse-temurin:21-jre AS final
ARG APP_VERSION=3.2.0-SNAPSHOT
ENV JAR_FILE=QuizzesApp-${APP_VERSION}.jar
ENV START_CLASS=org.anonymous.msrefactor.quizzes.QuizzesSimulator
COPY --from=run target/QuizzesApp-*.jar ./app.jar
CMD ["sh", "-c", "java -Dloader.main=$START_CLASS -Djava.security.egd=file:/dev/./urandom -jar ./app.jar"]