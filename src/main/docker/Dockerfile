FROM redhat/ubi9-minimal:latest
LABEL maintainer="MediaMarktSaturn Technology"

ARG CDXGEN_VERSION
ARG CDXGEN_PLUGINS_VERSION

RUN mkdir /root/.m2
COPY --chown=1001:root src/main/docker/assets/settings.xml /root/.m2/settings.xml
COPY --chown=1001:root src/main/docker/assets/git_askpass.sh /root/git_askpass.sh

WORKDIR /app
RUN chown 1001 /app \
    && chmod "g+rwX" /app \
    && chown 1001:root /app \
    && chmod "a+x" /root/git_askpass.sh

ENV GIT_ASKPASS='/root/git_askpass.sh'

# update system and install requirements
RUN \
    ln -fs /usr/share/zoneinfo/UTC /etc/localtime && \
    microdnf update -y && \
    microdnf install -y tzdata openssl ca-certificates zip unzip findutils fontconfig glibc-langpack-en git java-17 nodejs npm golang && \
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
    curl -s "https://get.sdkman.io" | bash > /dev/null && \
    source "$HOME/.sdkman/bin/sdkman-init.sh" && \
    sdk install maven && \
    sdk install gradle && \
    sdk install sbt

# cdxgen env
ENV JAVA_HOME="/usr/lib/jvm/jre" MAVEN_HOME="/root/.sdkman/candidates/maven/current" GRADLE_HOME="/root/.sdkman/candidates/gradle/current" MVN_CMD=mvn GRADLE_CMD=gradle
ENV PATH="$PATH:$MAVEN_HOME/bin:$GRADLE_HOME/bin"

COPY --chown=1001:root target/*-runner /app/technolinator

EXPOSE 8080
CMD ["/app/technolinator"]
