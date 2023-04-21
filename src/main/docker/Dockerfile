FROM redhat/ubi9-minimal:latest
LABEL maintainer="MediaMarktSaturn Technology"

ARG CDXGEN_VERSION
ARG CDXGEN_PLUGINS_VERSION

ARG APPLICATION_USER='app'
ARG APPLICATION_GROUP='apps'

ENV APP_DATA='/data'

RUN mkdir -p $APP_DATA/.m2 && \
    mkdir -p /app && \
    echo "${APPLICATION_USER}:x:201:100::${APP_DATA}:/sbin/nologin" >> /etc/passwd && \
    echo "${APPLICATION_GROUP}:x:101:${APPLICATION_USER}" >> /etc/group

COPY --chown=root:root --chmod=a-w src/main/docker/assets/git_askpass.sh /app/git_askpass.sh
COPY --chown=root:root --chmod=a-w src/main/docker/assets/run-java.sh /app/run-java.sh

WORKDIR $APP_DATA
RUN chown $APPLICATION_USER:$APPLICATION_GROUP -R $APP_DATA \
    && chown root:root /app \
    && chmod "a+x-w" -R /app

ENV GIT_ASKPASS='/app/git_askpass.sh'

# update system and install requirements
RUN \
    ln -fs /usr/share/zoneinfo/UTC /etc/localtime && \
    microdnf update -y && \
    microdnf install -y which tar tzdata openssl ca-certificates zip unzip findutils fontconfig glibc-langpack-en git nodejs npm golang && \
    microdnf clean all -y

# locale options
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'

# install cdxgen
RUN \
    npm install -g @cyclonedx/cdxgen@"$CDXGEN_VERSION" && \
    npm install -g @cyclonedx/cdxgen-plugins-bin@"$CDXGEN_PLUGINS_VERSION"

# install sdkman
COPY --chown=root:root --chmod=a-w src/main/docker/assets/curlrc /root/.curlrc
RUN curl -s "https://get.sdkman.io" | bash > /dev/null

ENV SDK_DIR="/app/sdks"

# install sdks
COPY --chown=root:root src/main/docker/assets/sdkman.config /root/.sdkman/etc/config
RUN source "/root/.sdkman/bin/sdkman-init.sh" && \
    sdk install java 17.0.7-tem && \
    sdk install java 20-tem && \
    sdk install maven && \
    sdk install gradle 7.6 && \
    sdk install sbt && \
    mv /root/.sdkman/candidates $SDK_DIR && \
    chown root:root -R $SDK_DIR && \
    chmod a-w -R $SDK_DIR && \
    rm -rf /root/.sdkman

# app & cdxgen env
ENV JAVA17_HOME="$SDK_DIR/java/17.0.7-tem" \
    JAVA20_HOME="$SDK_DIR/java/20-tem" \
    JAVA_HOME="$SDK_DIR/java/20-tem" \
    MAVEN_HOME="$SDK_DIR/maven/current" \
    GRADLE_HOME="$SDK_DIR/gradle/current" \
    MVN_CMD=mvn \
    GRADLE_CMD=gradle \
    GITHUB_USER="nobody" \
    SENSITIVE_ENV_VARS="QUARKUS_GITHUB_APP_APP_ID,QUARKUS_GITHUB_APP_WEBHOOK_SECRET,QUARKUS_GITHUB_APP_PRIVATE_KEY,GITHUB_TOKEN,DTRACK_APIKEY" \
    ALLOWED_ENV_SUBSTITUTIONS=""

ENV PATH="$PATH:$JAVA_HOME/bin:$MAVEN_HOME/bin:$GRADLE_HOME/bin"

ENV JAVA_OPTIONS="--enable-preview -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"

CMD ["/app/run-java.sh"]

COPY --chown=root:root --chmod=a-w target/quarkus-app/lib/ /app/lib/
COPY --chown=root:root --chmod=a-w target/quarkus-app/*.jar /app/
COPY --chown=root:root --chmod=a-w target/quarkus-app/app/ /app/app/
COPY --chown=root:root --chmod=a-w target/quarkus-app/quarkus/ /app/quarkus/

USER $APPLICATION_USER
