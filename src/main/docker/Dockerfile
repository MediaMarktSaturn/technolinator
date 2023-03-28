FROM redhat/ubi9-minimal:latest
LABEL maintainer="MediaMarktSaturn Technology"

ARG CDXGEN_VERSION
ARG CDXGEN_PLUGINS_VERSION

ARG APPLICATION_USER='app'
ARG APPLICATION_USER_ID='201'
ARG APPLICATION_GROUP='apps'
ARG APPLICATION_GROUP_ID='101'

RUN mkdir -p /app/.m2 && \
    echo "${APPLICATION_USER}:x:${APPLICATION_USER_ID}:100::/app:/sbin/nologin" >> /etc/passwd && \
    echo "${APPLICATION_GROUP}:x:${APPLICATION_GROUP_ID}:${APPLICATION_USER}" >> /etc/group

COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} src/main/docker/assets/git_askpass.sh /app/git_askpass.sh
COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} src/main/docker/assets/run-java.sh /app/run-java.sh

WORKDIR /app
RUN chown ${APPLICATION_USER_ID} -R /app \
    && chmod "g+rwX" /app \
    && chown ${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} /app \
    && chmod "a+x" /app/git_askpass.sh \
    && chmod "a+x" /app/run-java.sh

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

# install sdkman & sdks
RUN \
    echo "-s" > $HOME/.curlrc && \
    curl -s "https://get.sdkman.io" | bash > /dev/null

COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} src/main/docker/assets/sdkman.config $HOME/.sdkman/etc/config
COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} src/main/docker/assets/curlrc $HOME/.curlrc

ENV SDK_DIR="/app/sdks"

RUN source "$HOME/.sdkman/bin/sdkman-init.sh" && \
    sdk install java 17.0.6-tem && \
    sdk install java 20-tem && \
    sdk install maven && \
    sdk install gradle 7.6 && \
    sdk install sbt && \
    mv $HOME/.sdkman/candidates $SDK_DIR && \
    chown ${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} -R $SDK_DIR && \
    rm -rf $HOME/.sdkman

# app & cdxgen env
ENV JAVA17_HOME="$SDK_DIR/java/17.0.6-tem" \
    JAVA20_HOME="$SDK_DIR/java/20-tem" \
    JAVA_HOME="$SDK_DIR/java/20-tem" \
    MAVEN_HOME="$SDK_DIR/maven/current" \
    GRADLE_HOME="$SDK_DIR/gradle/current" \
    MVN_CMD=mvn \
    GRADLE_CMD=gradle \
    GITHUB_USER="nobody" \
    SENSITIVE_ENV_VARS="QUARKUS_GITHUB_APP_APP_ID,QUARKUS_GITHUB_APP_WEBHOOK_SECRET,QUARKUS_GITHUB_APP_PRIVATE_KEY,GITHUB_TOKEN,DTRACK_APIKEY"

ENV PATH="$PATH:$JAVA_HOME/bin:$MAVEN_HOME/bin:$GRADLE_HOME/bin"

ENV JAVA_OPTIONS="--enable-preview -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/app/quarkus-run.jar"

CMD ["/app/run-java.sh"]

COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} target/quarkus-app/lib/ /app/lib/
COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} target/quarkus-app/*.jar /app/
COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} target/quarkus-app/app/ /app/app/
COPY --chown=${APPLICATION_USER_ID}:${APPLICATION_GROUP_ID} target/quarkus-app/quarkus/ /app/quarkus/

USER $APPLICATION_USER
