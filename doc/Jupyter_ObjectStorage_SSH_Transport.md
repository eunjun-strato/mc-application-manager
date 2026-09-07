# Managed Jupyter Object Storage SSH transport

## 동작과 적용 범위

VM Jupyter 설치의 기본 전송 방식은 `SSH_TUNNEL`입니다.
사용자는 기존처럼 Object Storage, Jupyter token, 서비스 포트와 접근 CIDR을 지정합니다.
CSP access/secret key나 개발서버 주소를 노트북에 입력하지 않습니다.

- AM이 대상 VM의 SSH 포트(기본 22)에 연결합니다.
- VM의 앱별 private Unix socket을 AM의 loopback 전용 Object Storage relay에 연결합니다.
- Jupyter와 동일한 network namespace의 작은 bridge가 `127.0.0.1:18084`를 Unix socket으로 전달합니다.
- 노트북의 `MCMP_OBJECT_STORAGE_GATEWAY_URL`은 `http://127.0.0.1:18084/applications/object-storage-gateway`입니다.
- VM 호스트의 18084를 공개하거나 개발서버의 18084를 VM에 개방할 필요는 없습니다.
- 사용자의 브라우저 접속은 기존 Jupyter 서비스 포트(기본 8888)와 CIDR 제한을 사용합니다.

AM 컨테이너 내부에 OpenSSH client가 포함됩니다. VM은 Linux, Python 3, Docker와 기존 AM 명령 실행에 필요한 권한이 있어야 합니다.
AM 실행 환경에서 VM SSH 포트로 직접 연결할 수 있어야 하며, VM sshd가 remote stream-local forwarding을 허용해야 합니다.
Tumblebug만 SSH 가능한 환경에서는 AM의 SSH 경로도 확보해야 합니다.
VM은 실제 객체 전송을 위해 CSP Object Storage HTTPS endpoint에 접근할 수 있어야 합니다.

[OpenSSH remote Unix-socket forwarding 설명](https://man.openbsd.org/ssh.1#R~3)

## 설치·운영

- 설치: 컨테이너 실행 → 터널·bridge 구성 → Jupyter에서 grant 및 실제 객체 목록 조회 → 사용자 CIDR 규칙 추가 → 성공 처리.
- 설치 실패: grant 폐기, 생성된 터널·bridge와 컨테이너 정리를 시도하며 성공으로 처리하지 않습니다.
- 시작/재시작: Jupyter 실행 후 bridge와 SSH 연결을 복원하고 Object Storage 조회를 재검증합니다.
- 중지: 원하는 상태를 STOPPED로 기록하고 SSH·bridge를 정리합니다. 자동 복구가 중지된 앱을 다시 시작하지 않습니다.
- 삭제: SSH·bridge 정리 후 기존 컨테이너, grant, AM이 소유한 인바운드 규칙 정리 흐름으로 이어집니다.
- 정리 실패: DELETE_PENDING 상태를 남겨 재시도합니다. 다른 파일이나 다른 앱의 bridge를 삭제하지 않습니다. VM 접근 실패로 uninstall 자체가 실패했다면, 연결 복구 후 uninstall을 다시 실행해 컨테이너·grant·SG 정리까지 완료해야 합니다.

`object_storage_tunnel` 테이블에 대상 VM/컨테이너, VM 식별자, SSH host public key, 원하는 상태 및 lease를 보관합니다.
AM 재시작 시 ACTIVE 기록을 읽어 복구합니다. 정상 종료 시 lease를 반환하며, 비정상 종료 시 최대 약 120초 lease 만료 후 다음 복구 주기에 인계됩니다.
별도 heartbeat와 30초 reconciliation을 사용합니다. `APP_SCHEDULING_ENABLED=false`이면 자동 reconciliation은 비활성화됩니다.

SSH 개인키는 해당 VM의 키 리소스만 Tumblebug에서 가져와 AM의 소유자 전용 임시 디렉터리에 두며 정상 종료·정리 시 제거합니다.
개인키와 bearer token을 새 테이블에 저장하거나 로그에 출력하지 않습니다.
VM 교체/host key 변경은 자동 신뢰하지 않고 실패 처리합니다.

Relay는 Object Storage의 storages/objects/presigned-url API 및 readiness만 허용합니다.
기존 bearer grant의 버킷·prefix·읽기/쓰기 제한을 그대로 적용합니다.
앱별 UUID, private directory와 Docker ownership labels를 확인하며, 사용자 notebook volume을 삭제하지 않습니다.

## 설정

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| OBJECT_STORAGE_TRANSPORT | SSH_TUNNEL | 새 Jupyter 설치의 전송 방식 |
| OBJECT_STORAGE_SSH_EXECUTABLE | ssh | AM 호스트의 OpenSSH 실행 파일 |
| OBJECT_STORAGE_GATEWAY_PUBLIC_BASE_URL | 기존 설정 | DIRECT 모드에서만 사용하는, VM에서 접근 가능한 AM 주소 |
| APP_SCHEDULING_ENABLED | true | 다른 스케줄러와 함께 터널 자동 복구 활성화 |

`DIRECT`는 VM에서 AM endpoint로 직접 연결 가능한 환경의 명시적 호환 옵션입니다.
이미 SSH 방식으로 설치한 앱의 복구는 해당 테이블의 상태를 기준으로 합니다.

## 기존 설치와 배포 주의

이 변경을 AM에 배포하는 것만으로 기존 직접 접속 컨테이너의 environment나 notebook을 수정하지 않습니다.
새 설치부터 관리형 터널이 구성됩니다. 기존 설치 전환은 노트북/volume 백업 후 AM에서 재설치하는 절차로 진행합니다.
기존 수동 테스트용 `mcmp-jupyter-tunnel.service`, `mcmp-jupyter-ssh-bridge`와 notebook은 자동으로 가져오거나 삭제하지 않습니다.

기본 notebook은 `sample-data.ipynb`라는 이름으로 파일이 없을 때만 생성합니다. 기존 volume을 재사용하면 사용자가 수정한 notebook도 보존됩니다.
기존 파일에 gateway가 하드코딩되어 있다면 environment 기반 주소로 수정하거나 새 기본 notebook을 별도로 받아야 합니다.

DB를 초기화할 필요는 없습니다. 현재 `DDL_AUTO=update` 환경에서는 새 테이블이 생성됩니다.
스키마 변경을 별도로 관리하는 환경은 entity에 맞는 테이블/unique constraint를 먼저 추가해야 합니다.
기존 AM 이미지로 rollback하면 관리형 터널 자동 복구 기능도 없어집니다.

## 로컬 검증

일반 테스트는 운영 서버에 접속하지 않습니다. lease SQL은 독립적인 메모리 H2 DB에서 검증합니다.

```powershell
.\gradlew.bat test bootJar
docker build -t mcmp-am-tunnel-smoke:local src/test/docker/ssh-tunnel
$env:AM_TUNNEL_SSH_SMOKE = 'true'
.\gradlew.bat test --tests '*ObjectStorageTunnelSshSmokeTest'
```

선택 실행 SSH 테스트는 loopback에만 SSH를 publish한 임시 컨테이너와 임시 키를 만들고, 연결·relay 경로 제한·재연결 후 제거합니다.
Windows OpenSSH 9.5p2에서도 이 wire test를 통과했습니다.
CSP VM에서 AM 전체 설치 API를 실행한 검증과는 별개의 로컬 테스트입니다.

Python helper 안전성 테스트는 Linux Python 3에서 실행합니다.

```sh
python3 src/test/python/test_object_storage_tunnel.py
```
