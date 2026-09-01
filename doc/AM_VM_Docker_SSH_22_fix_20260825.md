# AM VM Docker Remote API(2375) 제거 및 SSH 22 전환 수정 내역

작성일: 2026-08-25

관련 선행 문서:

- [AM_VM_Docker_Remote_API_fix_20260707.md](./AM_VM_Docker_Remote_API_fix_20260707.md)

현재 상태:

- 로컬 소스 수정 및 전체 빌드/테스트 완료
- AWS 임시 VM을 이용한 Jenkins 실제 배포·제어·삭제 테스트 완료
- 개발 서버의 `mc-application-manager:ssh22-20260825-r3` 이미지에 반영 완료
- Git commit/PR 및 공식 registry image 배포는 아직 수행하지 않음

## 배경

기존 AM의 VM 애플리케이션 배포는 다음 두 통신 경로를 사용했다.

- Docker 준비 단계: AM -> CB-Tumblebug `cmd/infra` API -> SSH -> VM
- 컨테이너 배포·제어·모니터링 단계: AM -> VM Docker Remote API `tcp://{VM_PUBLIC_IP}:2375`

Docker Remote API 2375는 기본적으로 TLS와 인증이 없는 평문 Docker API이다. 이 포트에 접근할 수 있는 사용자는 컨테이너 생성, 호스트 디렉터리 마운트, privileged 컨테이너 실행 등을 통해 사실상 VM의 root 권한을 획득할 수 있다.

따라서 계정 정보를 알지 못하더라도 2375에 네트워크 접근만 가능하면 공격이 가능하다. 공인 IP에 직접 노출된 경우 위험이 가장 크지만, 사설망에서도 다른 워크로드가 침해되면 내부 이동 경로로 악용될 수 있다.

이번 수정의 전제 조건은 다음과 같았다.

- CB-Tumblebug은 별도 관리 프로젝트이므로 수정하지 않는다.
- 별도의 인증서 발급/관리 서버나 전용 VM을 추가하지 않는다.
- 대상 VM의 인바운드는 SSH 22만 허용하는 구성을 기준으로 한다.
- AM 프로젝트 수정만으로 Docker 배포·제어·모니터링 기능을 유지한다.
- Docker TLS Remote API 2376은 사용하지 않는다.

이 조건에서는 이미 CB-Tumblebug이 보유하고 있는 멀티클라우드 SSH 키와 `cmd/infra` API를 사용하는 방식이 가장 단순하다. AM은 SSH 개인 키를 직접 보관하지 않고, 기존처럼 CB-Tumblebug API 인증정보만 사용한다.

## 기존 문제

기존 구현은 다음과 같은 보안 및 운영 문제가 있었다.

- VM의 Docker daemon을 `0.0.0.0:2375`에 노출했다.
- 2375는 Docker 자체 인증을 제공하지 않으므로 네트워크 접근 권한만으로 Docker를 제어할 수 있었다.
- CSP 보안그룹을 잘못 설정하면 공인 인터넷에서 Docker API에 직접 접근할 수 있었다.
- AM은 VM 공인 IP를 기준으로 Docker API에 연결하므로 사설 IP 전용 VM 사용이 제한될 수 있었다.
- `/var/run/docker.sock` 권한을 `666`으로 변경하는 흐름이 있어 VM 내부의 모든 사용자가 Docker를 제어할 수 있었다.
- Docker Remote API 호출을 위해 `docker-java`와 HTTP transport 의존성을 유지해야 했다.
- HTTP 요청 자체가 성공하면 원격 shell 명령의 실제 종료 상태를 정확히 판별하기 어려웠다.
- 컨테이너 ID를 영속적으로 저장하지 않고 컨테이너 이름을 다시 검색하는 경로가 있어 동일 이름이나 상태 변경 시 오판 가능성이 있었다.
- 배포·시작·중지·재시작·삭제·로그·통계가 서로 다른 Docker client 경로에 분산되어 있었다.

## 변경 전후 구조

### 변경 전

```text
Docker 설치/설정
AM -> CB-Tumblebug API -> SSH 22 -> VM

Docker 배포/제어/모니터링
AM -> VM_PUBLIC_IP:2375 -> Docker daemon
```

필요한 VM 인바운드 포트:

- 22: CB-Tumblebug SSH
- 2375: AM의 Docker Remote API 접근
- 애플리케이션 서비스 포트

### 변경 후

```text
Docker 설치/설정/배포/제어/모니터링
AM -> CB-Tumblebug API -> SSH 22 -> VM -> /var/run/docker.sock
```

Docker 관리 목적으로 필요한 VM 인바운드 포트:

- 22: CB-Tumblebug SSH

2375와 2376은 사용하지 않는다. 애플리케이션 서비스 포트는 실제 사용자 접근 방식에 따라 별도로 허용한다.

## 수정 내용

### 0. VM Docker 대상 식별자 도입

추가 파일:

- `src/main/java/kr/co/mcmp/softwarecatalog/docker/model/DockerTarget.java`

Docker 명령 대상은 다음 세 값으로 식별한다.

- CB-Tumblebug namespace
- MCI/infra ID
- VM ID

각 값은 영문, 숫자, `.`, `_`, `-`만 허용하며 최대 길이를 제한한다. `/`, `?`, shell 메타문자 등을 거부하므로 다른 API 경로나 VM을 가리키도록 식별자를 조작할 수 없다.

### 1. CB-Tumblebug 단일 VM 명령 결과 구조화

대상 파일:

- `src/main/java/kr/co/mcmp/ape/cbtumblebug/api/CbtumblebugRestApi.java`
- `src/main/java/kr/co/mcmp/ape/cbtumblebug/dto/MciCommandResult.java`

`executeMciCommandResult()`를 추가하여 CB-Tumblebug 명령 응답의 `stdout`과 `stderr`를 분리해 반환한다.

주요 판정 기준:

- `results` 배열이 없거나 비어 있으면 실패한다.
- 정확히 한 VM을 지정했는데 결과가 2개 이상이면 모호한 결과로 보고 실패한다.
- 문자열, 배열, 객체 형태의 stdout/stderr 응답을 모두 파싱한다.
- HTTP 2xx 여부와 원격 shell 명령 성공 여부를 별도로 판정한다.

기존 `executeMciCommand()`는 호환성을 위해 유지하며 구조화된 결과의 stdout을 반환한다.

### 2. 공통 SSH Docker 명령 실행기 추가

추가 파일:

- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerSshCommandExecutor.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/docker/model/DockerCommandResult.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerCommandException.java`

모든 Docker shell 명령은 공통 실행기를 거친다.

명령 처리 흐름:

1. AM이 생성한 shell script를 UTF-8 Base64로 인코딩한다.
2. CB-Tumblebug `cmd/infra` API에 단일 VM ID와 함께 전달한다.
3. VM에서 Base64를 디코딩하여 subshell로 실행한다.
4. script 종료코드를 `__MCMP_EXIT_CODE__=<code>` 마커로 stdout 마지막에 출력한다.
5. AM이 마커와 종료코드를 파싱한다.
6. 종료코드가 0이 아니거나 완료 마커가 없으면 실패 처리한다.

Base64는 암호화 수단이 아니라 중첩 따옴표와 shell 구문이 외부 전송 명령을 변경하지 못하도록 하는 transport encoding이다. 이미지명, 컨테이너명, 포트, 컨테이너 ID 등 외부 입력은 별도 allowlist 검증과 POSIX shell quoting을 적용한다.

### 3. AM의 Docker 접근 경로를 Unix socket 전용으로 변경

대상 파일:

- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerSetupService.java`

새 준비 순서는 다음과 같다.

1. `docker --version`으로 Docker CLI 설치 여부를 확인한다.
2. Docker가 없으면 passwordless sudo 사용 가능 여부를 확인한다.
3. `get.docker.com` 설치 스크립트로 Docker를 설치하고 서비스를 활성화한다.
4. Tumblebug SSH 사용자를 `docker` 그룹에 추가한다.
5. 과거 AM이 작성한 정확한 systemd 2375 override를 발견하면 해당 인자만 제거하고 Docker를 재시작한다.
6. SSH 사용자가 로컬 Unix socket으로 `docker info`를 실행할 수 있는지 확인한다.
7. `ss -lntH`로 TCP 2375 listener가 없는지 확인한다.

정상 판정 마커:

```text
SECURE_DOCKER_READY
```

2375 listener 발견 시:

```text
INSECURE_2375_LISTENER
```

포트 검사 명령을 사용할 수 없는 경우:

```text
PORT_CHECK_UNAVAILABLE
```

2375가 열려 있거나 `ss`가 없어 닫힘을 검증할 수 없으면 안전한 방향으로 실패 처리한다. AM은 더 이상 다음 설정을 생성하지 않는다.

```text
-H tcp://0.0.0.0:2375
--host=tcp://0.0.0.0:2375
chmod 666 /var/run/docker.sock
```

자동 제거 대상은 과거 AM이 사용한 `/etc/systemd/system/docker.service.d/override.conf`의 정확한 2375 인자이다. `daemon.json`, 다른 systemd unit, 다른 포트 등의 사용자 정의 설정은 임의로 삭제하지 않는다. 이러한 설정으로 2375가 계속 열려 있으면 검증 단계에서 실패하므로 운영자가 원인을 확인해야 한다.

### 4. 컨테이너 생명주기를 Docker CLI over SSH로 변경

대상 파일:

- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerOperationService.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/application/service/impl/DockerDeploymentService.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/application/service/impl/DockerApplicationOperationService.java`

다음 기능을 Docker Remote API 대신 VM 내부 Docker CLI로 실행한다.

- 이미지 존재 여부 확인 및 pull
- 컨테이너 create/start
- 상태 확인
- stop/start/restart
- 강제 삭제 및 volume 제거
- 최근 로그 조회
- Docker host CPU 및 메모리 조회

허용하는 입력 조건:

- 이미지 reference: Docker registry/repository/tag/digest에 필요한 제한된 문자만 허용
- 컨테이너명: 영문, 숫자, `.`, `_`, `-`만 허용
- 포트: `1~65535` 범위의 `hostPort:containerPort`
- 컨테이너 ID: 12~64자리 소문자 16진수
- 로그 수: 1~1000줄
- 제어 action: `start`, `stop`, `restart` allowlist

컨테이너 생성 시 다음 관리 label을 기록한다.

```text
mcmp.managed=true
mcmp.namespace=<namespace>
mcmp.mci-id=<mciId>
mcmp.vm-id=<vmId>
mcmp.catalog-id=<catalogId>
mcmp.deployment-id=<deploymentId>
```

### 5. 컨테이너 ID 영속화

대상 파일:

- `src/main/java/kr/co/mcmp/softwarecatalog/application/model/ApplicationStatus.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/application/model/DeploymentHistory.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/application/service/ApplicationHistoryService.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/application/service/impl/ApplicationHistoryServiceImpl.java`

다음 테이블에 nullable `container_id varchar(64)` 컬럼을 추가한다.

- `application_status`
- `deployment_history`

신규 배포는 Docker가 반환한 실제 컨테이너 ID를 저장하고 이후 제어·모니터링에 사용한다.

기존 데이터에는 container ID가 없으므로 카탈로그 이름으로 컨테이너를 한 번 조회하고, 찾은 ID를 저장하는 호환 경로를 유지한다. 컬럼은 nullable이므로 이전 버전으로 애플리케이션만 롤백해도 기존 DB 데이터는 유지할 수 있다.

### 6. 로그 및 컨테이너 통계를 SSH 방식으로 변경

대상 파일:

- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/ContainerStatsCollector.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerMonitoringService.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerLogCollector.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/impl/DockerLogCollectorImpl.java`

한 번의 SSH 명령에서 다음 데이터를 수집한다.

- `docker inspect` 상태
- `docker stats --no-stream --format '{{json .}}'`
- CPU 사용률
- 메모리 사용률/사용량/limit
- 네트워크 input/output
- OOMKilled 여부
- restart count
- 첫 번째 publish port 및 VM 로컬 접근 가능 여부

기본 모니터링 scheduler 주기는 기존 설정과 동일하게 60초이다. `resource_metrics_history` 저장은 기존 정책대로 배포별 10분 간격이다.

컨테이너마다 별도의 CB-Tumblebug SSH 명령이 발생한다. 소규모 환경에서는 충분히 사용할 수 있으나, 동일 VM에 컨테이너가 많은 대규모 환경에서는 VM별 일괄 수집이나 동시성 제한을 후속 검토해야 한다.

### 7. docker-java 의존성과 기존 client 제거

대상 파일:

- `build.gradle`
- 삭제: `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/DockerClientFactory.java`
- 삭제: `src/main/java/kr/co/mcmp/softwarecatalog/docker/service/ContainerLogCollector.java`

제거된 의존성:

```gradle
com.github.docker-java:docker-java
com.github.docker-java:docker-java-transport-httpclient5
```

AM의 VM Docker 제어 경로에서는 더 이상 `tcp://{host}:2375` Docker client를 생성하지 않는다.

`NexusIntegrationServiceImpl` 내부의 AM 로컬 Docker daemon 기본 경로도 `tcp://localhost:2375`에서 `unix:///var/run/docker.sock`으로 변경했다. 이는 대상 VM SSH 제어와 별개인 로컬 Docker 명령 fallback 경로이다.

### 8. 예외 메시지 전달 보정

대상 파일:

- `src/main/java/kr/co/mcmp/ape/cbtumblebug/exception/CbtumblebugException.java`
- `src/main/java/kr/co/mcmp/softwarecatalog/application/exception/ApplicationException.java`

두 custom exception 생성자가 `RuntimeException`의 `super(message)`를 호출하도록 수정했다. 원격 명령 실패 사유가 `getMessage()`에서 null로 사라지지 않고 배포 결과와 로그에 전달된다.

## 보안 효과

이번 수정으로 얻는 직접적인 효과는 다음과 같다.

- VM의 인증 없는 Docker API를 네트워크에 노출할 필요가 없다.
- Docker 제어 트래픽은 CB-Tumblebug이 관리하는 SSH 22 경로를 사용한다.
- AM이 VM 공인 IP의 2375에 직접 접근하지 않는다.
- Docker 제어 대상이 namespace, MCI, VM ID로 명시된다.
- 명령 실패와 SSH 연결 중단을 성공으로 오판하지 않는다.
- 컨테이너 ID 및 명령 인자 검증으로 잘못된 대상 제어와 command injection 위험을 줄인다.
- Docker socket을 전체 사용자에게 `666`으로 열지 않는다.
- TLS Docker API 2376용 CA, client/server 인증서 발급·배포·갱신·폐기 체계가 필요하지 않다.

단, Docker 그룹 권한은 Linux host의 root에 준하는 권한이다. 권한 주체가 불특정 네트워크 사용자가 아니라 CB-Tumblebug이 SSH로 접속하는 관리 사용자로 제한되는 것이며, Tumblebug API 인증정보와 SSH 키 관리가 계속 중요하다.

## 보안그룹 및 방화벽 처리 범위

이번 AM 수정은 VM의 CSP 보안그룹 규칙을 자동 수정하지 않는다.

신규 동적 인프라는 CB-Tumblebug의 기본 SG template 정책에 따라 SSH 22만 허용하도록 구성할 수 있다. 실제 AWS E2E에서도 inbound 22만 열고 테스트했다.

기존 VM의 SG에 2375 허용 규칙이 남아 있다면 별도로 삭제해야 한다. VM에서 listener가 사라지면 즉시 악용 가능한 Docker API는 없어지지만, 불필요한 SG 규칙 자체도 제거하는 것이 원칙이다.

권장 확인:

```bash
sudo ss -lntp | grep ':2375'
```

정상 상태에서는 출력이 없어야 한다.

Docker daemon 설정 확인 예시:

```bash
systemctl cat docker
sudo cat /etc/docker/daemon.json
```

2375 설정이 있으면 제거한 뒤 Docker를 재시작한다.

## 유지되는 동작

- VM에 Docker가 없으면 자동 설치를 시도한다.
- Docker Hub 또는 설정된 registry에서 이미지를 pull한다.
- 단일 VM 및 다중 VM 배포 요청 구조는 유지한다.
- 컨테이너 start/stop/restart/uninstall API 계약을 유지한다.
- ApplicationStatus 및 DeploymentHistory 갱신 흐름을 유지한다.
- Docker container CPU, memory, network, OOM, restart 모니터링을 유지한다.
- Kubernetes Helm 배포, NGINX Ingress 설치 및 Kubernetes monitoring 경로는 변경하지 않는다.
- 애플리케이션 접근을 위한 public IP와 service port 정보는 화면 및 상태 데이터에 계속 사용할 수 있다.

## 변경되는 동작 및 호환성

### Docker가 없는 VM

자동 설치를 위해 다음 조건이 필요하다.

- VM에서 외부 `https://get.docker.com` 접근 가능
- passwordless sudo 가능
- systemd 기반 Docker service 사용 가능
- 설치 후 새 SSH session에서 docker 그룹 권한 적용 가능

Docker가 없고 passwordless sudo도 없으면 안전하지 않은 사용자 권한 2375 daemon을 띄우지 않고 배포를 실패시킨다.

### Docker가 이미 설치된 VM

SSH 사용자가 Unix socket으로 `docker info`를 실행할 수 있어야 한다. Docker가 실행 중이어도 해당 사용자가 docker 그룹에 없고 sudo 없이 접근할 수 없다면 배포를 실패시킨다.

### 기존 2375 VM

과거 AM의 정확한 systemd override는 자동 보정한다. 그 외 사용자 정의 설정은 임의 삭제하지 않고 2375 listener 검증에서 실패시킨다.

현재 AM이 생성한 기존 컨테이너 VM은 없었기 때문에, 기존 운영 VM의 in-place 전환 테스트 대신 신규 AWS VM에서 Docker 미설치 상태부터 전체 흐름을 검증했다.

### CB-Tumblebug 장애

Docker 준비뿐 아니라 배포·제어·모니터링도 CB-Tumblebug API와 SSH에 의존한다. Tumblebug 또는 대상 VM의 SSH가 장애 상태이면 Docker 작업은 실패하고 모니터링 상태가 `ERROR`로 기록될 수 있다.

### AM과 CB-Tumblebug 사이 통신

현재 Compose 환경에서 AM은 내부 Docker network의 CB-Tumblebug API에 Basic Auth를 HTTP로 전송한다. 이번 수정은 VM Docker 제어 경로의 2375 제거가 범위이며, AM-Tumblebug 구간의 HTTPS 전환은 포함하지 않았다.

같은 서버 내부 전용 network라면 외부 노출보다 위험이 낮지만, 컨테이너 network 내부까지 신뢰하지 않는 운영 환경에서는 reverse proxy 또는 Tumblebug 수신측 TLS 구성을 별도 검토해야 한다.

## 영향 범위

직접 영향:

- VM 타입 Docker 애플리케이션 배포 준비
- VM Docker 이미지 pull 및 컨테이너 생성
- VM 컨테이너 상태 조회와 생명주기 제어
- VM Docker 로그 수집
- VM Docker container resource monitoring
- VM 배포 이력 및 ApplicationStatus의 container ID 저장

직접 영향 없음:

- Kubernetes Helm 애플리케이션 배포
- Kubernetes namespace 처리
- NGINX Ingress Controller 설치 및 NodePort/TLS 설정
- 별도 MC-Observability 수집 경로
- CB-Tumblebug 자체 SSH 키 발급 및 저장 방식
- CSP VM/VNet/SG 생성 로직

## 추가 및 변경된 단위 테스트

주요 테스트 파일:

- `src/test/java/kr/co/mcmp/ape/cbtumblebug/api/CbtumblebugRestApiCommandTest.java`
- `src/test/java/kr/co/mcmp/softwarecatalog/docker/service/DockerSshCommandExecutorTest.java`
- `src/test/java/kr/co/mcmp/softwarecatalog/docker/service/DockerOperationServiceTest.java`
- `src/test/java/kr/co/mcmp/softwarecatalog/docker/service/DockerSetupServiceTest.java`
- `src/test/java/kr/co/mcmp/softwarecatalog/docker/service/ContainerStatsCollectorTest.java`

검증 항목:

- CB-Tumblebug stdout/stderr의 문자열, 배열, 객체 응답 파싱
- 한 VM 요청에 여러 결과가 반환되는 모호한 응답 거부
- 정상 종료코드 및 stderr 보존
- 원격 비정상 종료코드 전파
- SSH 중단 등으로 완료 마커가 없는 응답 거부
- shell 메타문자가 외부 transport 명령으로 노출되지 않음
- namespace/MCI/VM 경로 및 query injection 거부
- 잘못된 이미지명과 컨테이너명 거부
- 잘못된 포트 형식과 `0`, `65536` 경계값 거부
- `1`, `65535` 포트 경계값 허용
- 잘못된 컨테이너 ID 제어 거부
- Docker 설치/미설치 분기
- passwordless sudo가 없는 신규 VM 실패
- 과거 2375 override 제거
- 2375 listener가 남아 있으면 실패
- `ss`가 없어 포트 닫힘을 검증할 수 없으면 실패
- running/healthy 컨테이너 통계 파싱
- stopped/OOM 컨테이너 상태 파싱
- malformed optional metric이 lifecycle 상태를 훼손하지 않음

최종 로컬 검증:

```text
./gradlew clean check bootJar
tests=52
failures=0
errors=0
skipped=0
BUILD SUCCESSFUL
```

## AWS Jenkins 실제 E2E 테스트

### 비용 최소화 선택

검토한 인스턴스:

- `t3.micro`: 1 GiB, 약 $0.0104/hour
- `t3.small`: 2 GiB, 약 $0.0208/hour
- `t3a.small`: 2 GiB, 약 $0.0188/hour

Jenkins는 1 GiB에서 OOM 또는 긴 초기화 가능성이 있어, 2 GiB 중 가장 저렴한 `t3a.small`을 선택했다.

테스트 사양:

- CSP/region: AWS `us-east-1`
- VM: `t3a.small`, 2 vCPU, 2 GiB
- Image: Canonical Ubuntu 24.04 x86_64
- Root disk: gp3 8 GiB
- Monitoring agent 설치: `no`
- SG inbound: TCP 22만 허용
- Jenkins image: `jenkins/jenkins:2.504.3-lts-jdk17`
- Jenkins container port: 8080
- Tumblebug infra ID: `am-ssh22-e2e-20260825-1605`

### 검증 결과

- Docker 미설치 Ubuntu VM에 Docker 29.7.2 자동 설치 성공
- VM 22 접근 가능 확인
- VM 2375 접근 불가 확인
- VM 8080 공인 접근 불가 확인
- AM VM deploy API 성공
- Jenkins container `running` 확인
- VM 내부 Jenkins `/login` HTTP 200 확인
- DB에 저장된 container ID와 VM 실제 container ID 일치
- MCMP 관리 label 저장 확인
- AM STOP 성공 및 container `exited` 확인
- AM START 성공 및 동일 container ID 재사용 확인
- AM RESTART 성공 및 Jenkins HTTP 200 재확인
- AM UNINSTALL 성공 및 container 0개 확인
- uninstall 이후에도 2375 listener 없음 확인
- SSH 기반 monitoring snapshot 저장 확인

### 비용과 자원 정리

VM 생성부터 삭제까지 약 14분 사용했다. 인스턴스, gp3, public IPv4를 합친 추정 비용은 약 `$0.006`으로 1 cent 미만이다.

테스트 직후 다음 자원을 삭제했다.

- AWS VM instance
- 전용 security group
- 전용 SSH key
- 테스트에서 새로 생성한 shared VNet/subnet
- Tumblebug test infra
- AM 테스트 catalog, package, deployment/history/status/metric 데이터

최종 확인:

- Tumblebug infra 목록에 test ID 없음
- Tumblebug securityGroup 목록에 test resource 없음
- Tumblebug sshKey 목록에 test resource 없음
- Tumblebug vNet 목록에 test resource 없음
- CB-Spider VM/SG/keypair/VPC 목록에 AWS native resource ID 없음

## 개발 서버 반영 결과

배포 대상:

- `/home/ubuntu/mcmp/2026/0815/mc-admin-cli`
- Compose project: `mcc`
- 서비스: `mc-application-manager`
- 실행 이미지: `mc-application-manager:ssh22-20260825-r3`

최종 상태:

```text
status=running
health=healthy
restartCount=0
readyz={"message":"application-manager is ready"}
```

최종 검증 JAR SHA-256:

```text
fcd72c2b569919f72cec9a2c47dac002f0b3a9e6fdc49275370966ec5ace77be
```

DB 확인:

```text
application_status:container_id
deployment_history:container_id
```

Compose에서 custom image가 다음 플랫폼 설치 시 공식 0.6.0 이미지로 덮어써지지 않도록 현재 서버는 다음 값을 사용한다.

```yaml
mc-application-manager:
  image: mc-application-manager:ssh22-20260825-r3
  pull_policy: never
```

최종 r3 배포 직전 Compose 대비 변경은 image tag 한 줄뿐이다.

## 백업 및 롤백

보존한 백업:

- 최초 DB dump: `backups/am-ssh22-20260825-075140/application-manager.dump`
- 최초 Compose: `backups/am-ssh22-20260825-075140/docker-compose.yaml`
- r2 직전 Compose: `backups/am-ssh22-final-20260825-083130/docker-compose.yaml`
- r3 직전 Compose: `backups/am-ssh22-r3-20260825-084452/docker-compose.yaml`
- 이전 AM image tag: `mc-application-manager:ssh22-20260825-r2`
- 최초 image rollback tag: `mc-application-manager:pre-ssh22-20260825-075140`

r3에서 애플리케이션 기동 문제가 발생하면 r3 직전 Compose를 복원한다.

```bash
cd /home/ubuntu/mcmp/2026/0815/mc-admin-cli
cp backups/am-ssh22-r3-20260825-084452/docker-compose.yaml conf/docker/docker-compose.yaml
docker compose -p mcc \
  -f conf/docker/docker-compose.yaml \
  up -d --no-deps --force-recreate --pull never mc-application-manager
```

전체 2375 방식으로 롤백해야 하는 예외 상황에서는 최초 Compose와 DB dump를 사용한다. 다만 2375 재활성화는 보안 취약점을 복구하는 것이므로 장애 분석을 위한 최후 수단으로만 사용하고, 외부/사설 네트워크 모두에서 2375 접근을 먼저 차단해야 한다.

추가된 DB 컬럼은 nullable이므로 애플리케이션 image만 r2 또는 이전 버전으로 롤백할 때 제거할 필요가 없다.

## 운영 배포 전 확인 항목

- CB-Tumblebug `/readyz` 정상 여부
- AM에서 CB-Tumblebug API 인증 가능 여부
- Tumblebug에서 대상 VM의 SSH 22 접속 가능 여부
- VM SG inbound에서 2375/2376 미허용 여부
- VM의 `ss -lntH`에서 2375 listener 부재
- Docker 자동 설치가 필요하면 VM의 passwordless sudo와 외부 인터넷 접근 가능 여부
- 기존 Docker VM이면 Tumblebug SSH 사용자의 `docker info` 성공 여부
- `base64`, `sh`, `ss`, `docker` 명령 사용 가능 여부
- 애플리케이션 image tag/digest 고정 여부
- 실제 애플리케이션 서비스 포트의 SG 허용 범위

## 운영 배포 후 확인 항목

- AM `/readyz` 정상
- AM container health `healthy`
- 신규 VM Docker 설치 성공
- VM 2375 listener 없음
- VM SG에 2375 규칙 없음
- 단순 container deploy 성공
- DB의 `container_id`와 실제 Docker ID 일치
- STOP/START/RESTART/UNINSTALL 성공
- monitoring scheduler가 container 상태를 갱신
- SSH 실패 시 성공으로 오판하지 않고 명확한 오류 기록
- Compose 재실행 후 custom image 유지

## 아직 필요한 추가 검증

다음 항목은 이번 AWS Jenkins E2E에 포함하지 않았다.

### 1. 다른 CSP

- Azure
- GCP
- NCP
- KT Cloud

CSP별 기본 image의 SSH 사용자, passwordless sudo, systemd, Docker group 반영 방식을 확인해야 한다.

### 2. 기존 2375 VM in-place 전환

기존 AM 생성 VM이 확보되면 다음을 확인한다.

- 정확한 과거 override 자동 제거
- Docker restart 후 기존 container 영향
- container ID fallback 조회
- SG 2375 규칙 수동 제거

### 3. 다중 VM 애플리케이션

- Redis clustering
- Elasticsearch clustering
- VM 일부 성공/일부 실패
- 이미 설치된 VM skip 처리

### 4. 대규모 모니터링

- 동일 VM 다수 container
- 다수 MCI 동시 monitoring
- Tumblebug SSH 동시 요청 상한
- 60초 scheduler가 다음 주기와 겹치는지 여부
- VM별 일괄 조회 도입 필요성

## 테스트 환경에서 확인된 별도 사항

SSH 전환 기능 자체와 별개로 다음 환경 이슈가 관찰되었다.

- 비용/이미지 후보 조사 중 대용량 `lookupImages` 응답을 반복 조회하여 CB-Tumblebug container가 약 3.1 GiB 메모리를 사용한 뒤 OOM restart 1회 발생했다. 자동 복구되었으며 SSH Docker 생명주기 테스트 결과에는 영향을 주지 않았다. 일반 E2E에서는 bulk image lookup을 반복하지 않아야 한다.
- 테스트 도중 기존 KT infra 하나가 `mc-workflow-manager-jenkins` container 요청으로 삭제되었다. AM SSH 전환 테스트는 정확한 AWS test infra ID만 생성·삭제했으며 해당 KT 삭제 요청을 수행하지 않았다. 공유 검증 환경에서는 다른 workflow 실행과 테스트 시간을 분리하는 것이 안전하다.
- Compose 실행 시 `MC_INFRA_CONNECTOR_POSTGRES_HOST_PORT` 미설정 warning이 있었으나 기존 환경 warning이며 AM health와 SSH 테스트에는 영향을 주지 않았다.

## 판정 기준

이번 수정의 완료 판정 기준은 다음과 같다.

- AM VM Docker 제어 코드에 2375 client 생성 경로가 없어야 한다.
- AM의 Docker 제어는 VM 내부 Unix socket을 사용하고, TCP 2375 listener가 없어야 한다.
- 2375 listener가 있거나 닫힘을 확인할 수 없으면 배포를 실패시켜야 한다.
- CB-Tumblebug에서 정확히 한 VM을 지정하여 SSH 명령을 실행해야 한다.
- 원격 shell 종료코드를 판정하고 불완전 응답을 성공으로 처리하지 않아야 한다.
- 이미지명, 컨테이너명, 포트, 컨테이너 ID 입력을 검증해야 한다.
- 배포된 container ID를 저장하고 생명주기 및 monitoring에서 재사용해야 한다.
- Docker 미설치 VM에서 설치부터 Jenkins 실행까지 성공해야 한다.
- stop/start/restart/uninstall이 동일 container를 대상으로 동작해야 한다.
- 테스트용 AWS infra 및 연관 resource가 테스트 직후 삭제되어야 한다.
- 전체 단위 테스트와 build가 성공해야 한다.
- 개발 서버 AM이 새 image로 healthy 상태여야 한다.

현재 AWS Jenkins 검증 범위에서는 위 조건을 모두 만족한다.
