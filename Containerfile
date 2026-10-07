ARG THREDDS_BASE_IMAGE
FROM ${THREDDS_BASE_IMAGE} AS patch-builder
USER root
ARG NETCDF_JAVA_COMMIT
WORKDIR /work
COPY scripts/build-patched-jars.sh /usr/local/bin/build-patched-jars.sh
COPY tests/NcmlReferenceTest.java /work/tests/NcmlReferenceTest.java
RUN chmod 0755 /usr/local/bin/build-patched-jars.sh && \
    /usr/local/bin/build-patched-jars.sh "${NETCDF_JAVA_COMMIT}" /work/out

ARG THREDDS_BASE_IMAGE
FROM ${THREDDS_BASE_IMAGE}
ARG THREDDS_BASE_IMAGE
ARG NETCDF_JAVA_COMMIT
ARG IMAGE_VERSION
ARG SOURCE_URL
ARG VCS_REF
ARG BUILD_DATE
LABEL org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.base.name="${THREDDS_BASE_IMAGE}" \
      io.thredds.netcdf-java.commit="${NETCDF_JAVA_COMMIT}"
COPY --from=patch-builder /work/out/cdm-s3-5.9.1.jar \
  /usr/local/tomcat/webapps/thredds/WEB-INF/lib/cdm-s3-5.9.1.jar
COPY --from=patch-builder /work/out/cdm-core-5.9.1.jar \
  /usr/local/tomcat/webapps/thredds/WEB-INF/lib/cdm-core-5.9.1.jar
COPY --from=patch-builder /work/out/sha256sums.txt \
  /opt/thredds-swiftfix/sha256sums.txt
