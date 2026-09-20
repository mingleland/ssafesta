#!/usr/bin/env bash
# Backend의 R2 presigned PUT·HEAD·body SHA-256·멱등 완료 계약을 네트워크 없이 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"

cd "${repo_root}/backend"
./mvnw test \
  -Dtest=com.example.ssafesta.storage.S3ObjectStorageTest,com.example.ssafesta.ai.AiDocumentUploadIntegrationTest
