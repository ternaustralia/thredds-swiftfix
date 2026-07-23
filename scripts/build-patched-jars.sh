#!/usr/bin/env bash
set -Eeuo pipefail

COMMIT="${1:?Missing netCDF-Java commit}"
OUT="${2:?Missing output directory}"
LIB=/usr/local/tomcat/webapps/thredds/WEB-INF/lib
JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
WORK=/tmp/netcdf-java-swiftfix

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/src/thredds/filesystem/s3" \
  "$WORK/src/ucar/nc2/internal/ncml" \
  "$WORK/classes/s3" "$WORK/classes/core" "$OUT"

S3="$WORK/src/thredds/filesystem/s3/ControllerS3.java"
CORE="$WORK/src/ucar/nc2/internal/ncml/AggregationExisting.java"

curl -fsSL "https://raw.githubusercontent.com/Unidata/netcdf-java/${COMMIT}/cdm/s3/src/main/java/thredds/filesystem/s3/ControllerS3.java" -o "$S3"
curl -fsSL "https://raw.githubusercontent.com/Unidata/netcdf-java/${COMMIT}/cdm/core/src/main/java/ucar/nc2/internal/ncml/AggregationExisting.java" -o "$CORE"

awk '
{
  print
  if ($0 ~ /^[[:space:]]*String continuationToken = useV2 \? responseV2\.nextContinuationToken\(\) : responseV1\.nextMarker\(\);[[:space:]]*$/) {
    print ""
    print "            // OpenStack Swift may omit NextMarker for a truncated V1 response."
    print "            if (!useV2"
    print "                && continuationToken == null"
    print "                && responseV1.isTruncated()"
    print "                && !objects.isEmpty()) {"
    print "              continuationToken = objects.get(objects.size() - 1).key();"
    print "            }"
  }
}
' "$S3" > "$S3.patched"
mv "$S3.patched" "$S3"
grep -Fq 'continuationToken = objects.get(objects.size() - 1).key();' "$S3"

OLD='Parse.readRootElement("file:" + cacheFile.getPath())'
NEW='Parse.readRootElement(cacheFile.toURI().toString())'
grep -Fq "$OLD" "$CORE"
sed -i "s@${OLD}@${NEW}@" "$CORE"
grep -Fq "$NEW" "$CORE"

"$JAVA_HOME/bin/javac" -proc:none -cp "$LIB/*" -d "$WORK/classes/s3" "$S3"
"$JAVA_HOME/bin/javac" -proc:none -cp "$LIB/*" -d "$WORK/classes/core" "$CORE"

cp "$LIB/cdm-s3-5.9.1.jar" "$OUT/cdm-s3-5.9.1.jar"
cp "$LIB/cdm-core-5.9.1.jar" "$OUT/cdm-core-5.9.1.jar"

"$JAVA_HOME/bin/jar" --update --file "$OUT/cdm-s3-5.9.1.jar" \
  -C "$WORK/classes/s3" thredds/filesystem/s3
"$JAVA_HOME/bin/jar" --update --file "$OUT/cdm-core-5.9.1.jar" \
  -C "$WORK/classes/core" ucar/nc2/internal/ncml/AggregationExisting.class

"$JAVA_HOME/bin/javap" -classpath "$OUT/cdm-core-5.9.1.jar" -c -p \
  ucar.nc2.internal.ncml.AggregationExisting | grep -q 'java/io/File.toURI'

sha256sum "$OUT/cdm-s3-5.9.1.jar" "$OUT/cdm-core-5.9.1.jar" \
  > "$OUT/sha256sums.txt"
cat "$OUT/sha256sums.txt"
