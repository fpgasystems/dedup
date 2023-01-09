#! /bin/sh

set -o errexit
set -o nounset
set -o xtrace

MILL_VERSION=0.10.3

if [ ! -f mill ]; then
  curl -JLO https://github.com/com-lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION && mv $MILL_VERSION mill && chmod +x mill
fi

./mill version

#./mill hwsys.test
./mill hwsys.test.testSim dedup.Sha3Tests
#FIXME: CRCTests sometimes fail due to the sw reference issue
# mill hwsys.test.testSim dedup.CRCTests


