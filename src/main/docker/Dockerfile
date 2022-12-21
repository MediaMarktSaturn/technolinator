FROM redhat/ubi9-minimal:latest
LABEL maintainer="MediaMarktSaturn Technology"

WORKDIR /app/
RUN chown 1001 /app \
    && chmod "g+rwX" /app \
    && chown 1001:root /app

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
    npm install -g @appthreat/cdxgen && \
    npm install -g @ngcloudsec/cdxgen-plugins-bin

# install sdkman & sdks
RUN \
    echo "-s" > $HOME/.curlrc && \
    curl -s "https://get.sdkman.io" | bash > /dev/null && \
    source "$HOME/.sdkman/bin/sdkman-init.sh" && \
    sdk install maven && \
    sdk install gradle && \
    sdk install sbt

# cdxgen env
ENV FETCH_LICENSE=true MVN_CMD=mvn GRADLE_CMD=gradle USE_GOSUM=true

COPY --chown=1001:root target/*-runner /app/technolinator

EXPOSE 8080
#CMD ["/app/technolinator"]
CMD ["env"]
