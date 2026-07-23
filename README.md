# ternaustralia/thredds-swiftfix starter

This starter builds an OCI image from a pinned official THREDDS 5.8 image and
patches both bundled netCDF-Java 5.9.1 jars.

Before building:

1. Copy `versions.env.example` to `versions.env`.
2. Replace the base-image digest.
3. Replace the netCDF-Java commit SHA.
4. Run:

```bash
docker build -f Containerfile   --build-arg THREDDS_BASE_IMAGE="$(grep '^THREDDS_BASE_IMAGE=' versions.env | cut -d= -f2-)"   --build-arg NETCDF_JAVA_COMMIT="$(grep '^NETCDF_JAVA_COMMIT=' versions.env | cut -d= -f2-)"   --build-arg IMAGE_VERSION="$(grep '^IMAGE_VERSION=' versions.env | cut -d= -f2-)"   --build-arg SOURCE_URL='https://github.com/ternaustralia/thredds-swiftfix'   --build-arg VCS_REF="$(git rev-parse HEAD)"   --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"   -t ternaustralia/thredds-swiftfix:local .
```
