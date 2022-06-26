#!/bin/bash

pushd . > /dev/null

cd "$( cd "$( dirname "${BASH_SOURCE[0]:-${(%):-%x}}" )" >/dev/null 2>&1 && pwd )"
cd ..

export SF=0.003
export POSTGRES_CSV_DIR=`pwd`/social-network-sf${SF}-bi-composite-merged-fk/graphs/csv/bi/composite-merged-fk/

popd > /dev/null
