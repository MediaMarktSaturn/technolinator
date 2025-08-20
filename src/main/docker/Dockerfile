FROM redhat/ubi9-minimal:latest
LABEL maintainer="MediaMarktSaturn Technology" \
      home="https://github.com/MediaMarktSaturn/technolinator" \
      license="Apache-2.0"

ARG CDXGEN_VERSION
ARG CDXGEN_PLUGINS_VERSION
ARG GRYPE_VERSION
ARG SBOMQS_VERSION
ARG NYDUS_VERSION
ARG DEPSCAN_VERSION

# sdkman provided jdks
ARG JAVA_17_VERSION='17.0.16-tem'
ARG JAVA_21_VERSION='21.0.8-tem'
ARG JAVA_23_VERSION='23.0.2-tem'
ARG GRADLE_VERSION='9.0.0'
ARG NODE_VERSION='21'
ARG GO_VERSION='1.24.6'
ARG MAVEN_VERSION='3.9.11'

# location of application binaries and configuration
ENV APP_DIR='/app'

# 'home' directory of the app user, target location for (persistent) caches
ENV APP_DATA='/data'

ARG APPLICATION_USER='app'
ARG APPLICATION_GROUP='apps'

RUN mkdir -p $APP_DATA/.m2 && \
    mkdir -p $APP_DIR && \
    echo "${APPLICATION_USER}:x:201:100::${APP_DATA}:/sbin/nologin" >> /etc/passwd && \
    echo "${APPLICATION_GROUP}:x:101:${APPLICATION_USER}" >> /etc/group

COPY --chown=root:root --chmod=a-w src/main/docker/assets/git_askpass.sh $APP_DIR/git_askpass.sh
COPY --chown=root:root --chmod=a-w src/main/docker/assets/run-java.sh $APP_DIR/run-java.sh
COPY --chown=root:root --chmod=a-w src/main/docker/assets/grype.tmpl $APP_DIR/grype.tmpl
COPY --chown=root:root --chmod=a-w src/main/docker/assets/depscan.j2 $APP_DIR/depscan.j2

WORKDIR $APP_DATA
RUN chown $APPLICATION_USER:$APPLICATION_GROUP -R $APP_DATA \
    && chown root:root $APP_DIR \
    && chmod "a+x-w" -R $APP_DIR

ENV GIT_ASKPASS="$APP_DIR/git_askpass.sh"

# update system and install requirements
RUN \
    microdnf update --nodocs -y && \
    microdnf reinstall --nodocs -y tzdata && \
    ln -fs /usr/share/zoneinfo/UTC /etc/localtime && \
    microdnf install --nodocs -y which tar openssl ca-certificates zip unzip findutils fontconfig glibc-langpack-en git python3 pip php php-cli php-zip php-json && \
    curl -s https://raw.githubusercontent.com/composer/getcomposer.org/main/web/installer | php -- --filename=composer --install-dir=/usr/bin --quiet && \
    microdnf clean all -y

ENV NODE_HOME="$APP_DIR/node$NODE_VERSION"

# install nodejs and npm using nvm (node version manager) to get the latest version
RUN \
    curl -sf https://raw.githubusercontent.com/creationix/nvm/master/install.sh | bash && \
    . /root/.nvm/nvm.sh && \
    nvm install 21 && \
    mv /root/.nvm/versions/node/* $NODE_HOME && \
    rm -rf /root/.nvm

ENV GO_HOME="$APP_DIR/go"

# install golang
RUN \
    cd $APP_DIR && \
    curl -Ls https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz -o go.tar.gz && \
    tar xf go.tar.gz && rm -rf go.tar.gz && \
    rm -rf $GO_HOME/src $GO_HOME/test

# locale options
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'

# install cdxgen and relatives
RUN \
    export PATH="$PATH:$NODE_HOME/bin" && \
    npm install -g @appthreat/atom && \
    npm install -g @cyclonedx/cdxgen@"$CDXGEN_VERSION" && \
    npm install -g @cyclonedx/cdxgen-plugins-bin@"$CDXGEN_PLUGINS_VERSION"

# install grype
RUN \
    curl -Ls https://github.com/anchore/grype/releases/download/v${GRYPE_VERSION}/grype_${GRYPE_VERSION}_linux_amd64.tar.gz -o grype.tar.gz && \
    tar xf grype.tar.gz && \
    mv grype /usr/bin/grype && \
    rm -f grype.tar.gz

# install sbomqs
RUN \
    curl -Ls https://github.com/interlynk-io/sbomqs/releases/download/v${SBOMQS_VERSION}/sbomqs-linux-amd64 -o sbomqs && \
    chmod a+x sbomqs && \
    mv sbomqs /usr/bin/sbomqs

# install nydus
RUN \
    curl -Ls "https://github.com/dragonflyoss/nydus/releases/download/v${NYDUS_VERSION}/nydus-static-v${NYDUS_VERSION}-linux-amd64.tgz" -o nydus.tgz && \
    tar xf nydus.tgz && \
    mv nydus-static/* /usr/bin/ && \
    rm -rf nydus.tgz nydus-static

# install depscan
RUN pip install -q owasp-depscan==${DEPSCAN_VERSION}

# install sdkman
COPY --chown=root:root --chmod=a-w src/main/docker/assets/curlrc /root/.curlrc
RUN curl -s "https://get.sdkman.io" | bash > /dev/null

ENV SDK_DIR="$APP_DIR/sdks"

# install sdks
COPY --chown=root:root src/main/docker/assets/sdkman.config /root/.sdkman/etc/config
RUN source "/root/.sdkman/bin/sdkman-init.sh" && \
    sdk install java $JAVA_17_VERSION && \
    sdk install java $JAVA_23_VERSION && \
    sdk install java $JAVA_21_VERSION && \
    sdk install maven $MAVEN_VERSION && \
    sdk install gradle $GRADLE_VERSION && \
    sdk install sbt && \
    mv /root/.sdkman/candidates $SDK_DIR && \
    chown root:root -R $SDK_DIR && \
    chmod a-w -R $SDK_DIR && \
    rm -rf /root/.sdkman

# app & tools env
ENV JAVA17_HOME="$SDK_DIR/java/$JAVA_17_VERSION" \
    JAVA23_HOME="$SDK_DIR/java/$JAVA_23_VERSION" \
    JAVA21_HOME="$SDK_DIR/java/$JAVA_21_VERSION" \
    JAVA_HOME="$SDK_DIR/java/$JAVA_21_VERSION" \
    MAVEN_HOME="$SDK_DIR/maven/current" \
    GRADLE_HOME="$SDK_DIR/gradle/current" \
    MVN_CMD=mvn \
    GRADLE_CMD=gradle \
    GITHUB_USER="nobody" \
    GRYPE_TEMPLATE="$APP_DIR/grype.tmpl" \
    DEPSCAN_TEMPLATE="$APP_DIR/depscan.j2" \
    INTERLYNK_DISABLE_VERSION_CHECK="true" \
    GRYPE_CHECK_FOR_APP_UPDATE="false" \
    SENSITIVE_ENV_VARS="QUARKUS_GITHUB_APP_APP_ID,QUARKUS_GITHUB_APP_WEBHOOK_SECRET,QUARKUS_GITHUB_APP_PRIVATE_KEY,GITHUB_TOKEN,DTRACK_APIKEY" \
    ALLOWED_ENV_SUBSTITUTIONS=""

ENV PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$NODE_HOME/bin:$JAVA_HOME/bin:$MAVEN_HOME/bin:$GRADLE_HOME/bin:$GO_HOME/bin"

ENV JAVA_OPTIONS="-Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="$APP_DIR/quarkus-run.jar"

CMD ["/app/run-java.sh"]

COPY --chown=root:root --chmod=a-w target/quarkus-app/lib/ $APP_DIR/lib/
COPY --chown=root:root --chmod=a-w target/quarkus-app/*.jar $APP_DIR/
COPY --chown=root:root --chmod=a-w target/quarkus-app$APP_DIR/ $APP_DIR/app/
COPY --chown=root:root --chmod=a-w target/quarkus-app/quarkus/ $APP_DIR/quarkus/

USER $APPLICATION_USER
