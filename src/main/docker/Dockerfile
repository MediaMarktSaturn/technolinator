FROM redhat/ubi10-minimal:latest
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
ARG JAVA_17_VERSION='17.0.21.1+2-tem'
ARG JAVA_21_VERSION='21.0.13.1+2-tem'
ARG JAVA_25_VERSION='25.0.5.1+2-tem'
ARG GRADLE_VERSION='9.9.3'
ARG NODE_VERSION='26'
ARG GO_VERSION='1.27.4'
ARG MAVEN_VERSION='3.10.2'
ARG FLUTTER_VERSION='3.50.1'
ARG FLUTTER_SHA256='4ad17fc8462fbcef2ba19ff4a78c0a16b7147fde1add8578e267ea62cdb92678'
ARG ANDROID_TOOLS_VERSION='15859902'
ARG ANDROID_TOOLS_SHA1='040d3996a65543d22ec4bf73e4c37aa37a8d4af4'

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
COPY --chown=root:root --chmod=0555 src/main/docker/assets/technolinator-flutter $APP_DIR/bin/technolinator-flutter

WORKDIR $APP_DATA
RUN chown $APPLICATION_USER:$APPLICATION_GROUP -R $APP_DATA \
    && chown root:root $APP_DIR \
    && chmod "a+x-w" -R $APP_DIR

ENV GIT_ASKPASS="$APP_DIR/git_askpass.sh"

# update system and install requirements
RUN \
    microdnf update --nodocs -y && \
    microdnf install --nodocs -y tzdata && \
    ln -fs /usr/share/zoneinfo/UTC /etc/localtime && \
    microdnf install --nodocs -y which tar xz openssl ca-certificates zip unzip findutils fontconfig glibc-langpack-en git python3 pip php php-cli php-zip php-json && \
    microdnf install --nodocs -y ruby ruby-devel rubygem-bundler gcc gcc-c++ make redhat-rpm-config && \
    curl -s https://raw.githubusercontent.com/composer/getcomposer.org/main/web/installer | php -- --filename=composer --install-dir=/usr/bin --quiet && \
    microdnf clean all -y

ENV NODE_HOME="$APP_DIR/node$NODE_VERSION"

# install nodejs and npm using nvm (node version manager) to get the latest version
RUN \
    curl -sf https://raw.githubusercontent.com/creationix/nvm/master/install.sh | bash && \
    . /root/.nvm/nvm.sh && \
    nvm install $NODE_VERSION && \
    mv /root/.nvm/versions/node/* $NODE_HOME && \
    rm -rf /root/.nvm

# install yarn
RUN \
    export PATH="$PATH:$NODE_HOME/bin" && \
    npm install -g yarn

ENV GO_HOME="$APP_DIR/go"

# install golang
RUN \
    cd $APP_DIR && \
    curl -Ls https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz -o go.tar.gz && \
    tar xf go.tar.gz && rm -rf go.tar.gz

# locale options
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'

# install cdxgen and relatives
RUN \
    export PATH="$PATH:$NODE_HOME/bin" && \
    npm install @appthreat/atom-parsetools && \
    npm install -g @cyclonedx/cdxgen@"$CDXGEN_VERSION" && \
    npm install -g @cdxgen/cdxgen-plugins-bin@"$CDXGEN_PLUGINS_VERSION" && \
    curl -sLO https://github.com/AppThreat/atom/releases/latest/download/atom-amd64 && \
    chmod a+x atom-amd64 && mv atom-amd64 /usr/bin/atom

# install grype
RUN \
    curl -Ls https://github.com/anchore/grype/releases/download/v${GRYPE_VERSION}/grype_${GRYPE_VERSION}_linux_amd64.tar.gz -o grype.tar.gz && \
    tar xf grype.tar.gz && \
    mv grype /usr/bin/grype && \
    rm -f grype.tar.gz

# install sbomqs
RUN \
    curl -Ls https://github.com/interlynk-io/sbomqs/releases/download/v${SBOMQS_VERSION}/sbomqs_${SBOMQS_VERSION}_Linux_x86_64.tar.gz -o sbomqs.tgz && \
    tar -xzf sbomqs.tgz && \
    chmod a+x sbomqs && \
    mv sbomqs /usr/bin/sbomqs

# install nydus
RUN \
    curl -Ls "https://github.com/dragonflyoss/nydus/releases/download/v${NYDUS_VERSION}/nydus-static-v${NYDUS_VERSION}-linux-amd64.tgz" -o nydus.tgz && \
    tar xf nydus.tgz && \
    mv nydus-static/* /usr/bin/ && \
    rm -rf nydus.tgz nydus-static

# install depscan
RUN pip install -q owasp-depscan==${DEPSCAN_VERSION#v}

# install sdkman
COPY --chown=root:root --chmod=a-w src/main/docker/assets/curlrc /root/.curlrc
RUN curl -s "https://get.sdkman.io" | bash > /dev/null

ENV SDK_DIR="$APP_DIR/sdks"

# install sdks
COPY --chown=root:root src/main/docker/assets/sdkman.config /root/.sdkman/etc/config
RUN source "/root/.sdkman/bin/sdkman-init.sh" && \
    sdk install java $JAVA_17_VERSION && \
    sdk install java $JAVA_25_VERSION && \
    sdk install java $JAVA_21_VERSION && \
    sdk install maven $MAVEN_VERSION && \
    sdk install gradle $GRADLE_VERSION && \
    sdk install sbt && \
    mv /root/.sdkman/candidates $SDK_DIR && \
    chown root:root -R $SDK_DIR && \
    chmod a-w -R $SDK_DIR && \
    rm -rf /root/.sdkman

# Flutter updates its own cache even for `pub get`, so the installed SDKs are read-only templates.
# `technolinator-flutter` gives each analysis an overlay of symbolic links to them, see docs/Adoption.md.
# The pub cache the Flutter tool is built with is kept inside of the SDK and readable: the tool refers to it by absolute paths,
# and would try to rebuild itself (i.e. write to the SDK) if it couldn't read them. Removing the stamp makes the launcher rebuild
# the tool here with that pub cache, instead of keeping the prebuilt one of the release archive.
RUN mkdir -p "$SDK_DIR/flutter" && \
    curl -fL "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_${FLUTTER_VERSION}-stable.tar.xz" -o /tmp/flutter.tar.xz && \
    echo "$FLUTTER_SHA256  /tmp/flutter.tar.xz" | sha256sum -c - && \
    tar -xf /tmp/flutter.tar.xz -C "$SDK_DIR/flutter" && \
    mv "$SDK_DIR/flutter/flutter" "$SDK_DIR/flutter/$FLUTTER_VERSION" && \
    rm /tmp/flutter.tar.xz && \
    chown -R root:root "$SDK_DIR/flutter" && \
    rm -f "$SDK_DIR/flutter/$FLUTTER_VERSION/bin/cache/flutter_tools.stamp" && \
    CI=true PUB_CACHE="$SDK_DIR/flutter/$FLUTTER_VERSION/.pub-cache" "$SDK_DIR/flutter/$FLUTTER_VERSION/bin/flutter" --suppress-analytics precache --android && \
    rm -rf "$SDK_DIR/flutter/$FLUTTER_VERSION/.pub-preload-cache" && \
    test -s "$SDK_DIR/flutter/$FLUTTER_VERSION/bin/cache/flutter.version.json" && \
    test -s "$SDK_DIR/flutter/$FLUTTER_VERSION/bin/cache/flutter_tools.snapshot" && \
    test -s "$SDK_DIR/flutter/$FLUTTER_VERSION/bin/cache/engine.stamp" && \
    test -e "$SDK_DIR/flutter/$FLUTTER_VERSION/bin/cache/engine.realm" && \
    python3 -c 'import json, os, sys, urllib.parse; config, sdk = sys.argv[1], sys.argv[2]; base = "file://" + os.path.dirname(config) + "/"; roots = [urllib.parse.urlparse(urllib.parse.urljoin(base, p["rootUri"])).path for p in json.load(open(config))["packages"]]; bad = [r for r in roots if not os.path.realpath(r).startswith(sdk + "/") or not os.path.isfile(os.path.join(r, "pubspec.yaml"))]; sys.exit("packages of the Flutter tool outside of the SDK or missing: %s" % bad if bad else 0)' \
        "$SDK_DIR/flutter/$FLUTTER_VERSION/packages/flutter_tools/.dart_tool/package_config.json" "$(realpath "$SDK_DIR/flutter/$FLUTTER_VERSION")" && \
    chmod -R a+rX,a-w "$SDK_DIR/flutter"

ENV ANDROID_HOME="$SDK_DIR/android" \
    ANDROID_SDK_ROOT="$SDK_DIR/android"

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" && \
    curl -fL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_TOOLS_VERSION}_latest.zip" -o /tmp/android-tools.zip && \
    echo "$ANDROID_TOOLS_SHA1  /tmp/android-tools.zip" | sha1sum -c - && \
    unzip -q /tmp/android-tools.zip -d "$ANDROID_HOME/cmdline-tools" && \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" && \
    rm /tmp/android-tools.zip && \
    export JAVA_HOME="$SDK_DIR/java/$JAVA_21_VERSION" && \
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null && \
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358" && \
    chmod -R a-w "$ANDROID_HOME"

# app & tools env
ENV JAVA17_HOME="$SDK_DIR/java/$JAVA_17_VERSION" \
    JAVA25_HOME="$SDK_DIR/java/$JAVA_25_VERSION" \
    JAVA21_HOME="$SDK_DIR/java/$JAVA_21_VERSION" \
    JAVA_HOME="$SDK_DIR/java/$JAVA_25_VERSION" \
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

# ruby gems are installed by the unprivileged app user (via bundler), so use a writable location
ENV GEM_HOME="$APP_DATA/.gem" \
    BUNDLE_USER_HOME="$APP_DATA/.bundle"

ENV PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$NODE_HOME/bin:$JAVA_HOME/bin:$MAVEN_HOME/bin:$GRADLE_HOME/bin:$GO_HOME/bin:$GEM_HOME/bin:$APP_DIR/bin"

ENV JAVA_OPTIONS="-Djava.util.logging.manager=org.jboss.logmanager.LogManager --add-opens java.base/java.lang=ALL-UNNAMED"
ENV JAVA_APP_JAR="$APP_DIR/quarkus-run.jar"

CMD ["/app/run-java.sh"]

COPY --chown=root:root --chmod=a-w target/quarkus-app/lib/ $APP_DIR/lib/
COPY --chown=root:root --chmod=a-w target/quarkus-app/*.jar $APP_DIR/
COPY --chown=root:root --chmod=a-w target/quarkus-app$APP_DIR/ $APP_DIR/app/
COPY --chown=root:root --chmod=a-w target/quarkus-app/quarkus/ $APP_DIR/quarkus/

USER $APPLICATION_USER
