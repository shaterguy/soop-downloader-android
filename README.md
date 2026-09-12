# SOOP Downloader

SOOP 다시보기·캐치 링크를 받아 제공되는 최고 화질의 영상을 Android 기기에 저장합니다. 다운로드 서버 없이 기기에서 직접 처리합니다.

## 사용

1. SOOP 앱에서 영상의 공유 버튼을 누르고 **SOOP Downloader**를 선택합니다. 추가 확인 버튼 없이 다운로드가 시작됩니다.
2. 앱에서 주소를 붙여 넣고 **다운로드**를 눌러도 됩니다.
3. 완료한 영상은 **Movies/SOOP Downloader**, 갤러리, 앱의 저장 기록에서 확인합니다.

진행 알림·취소·중복 다운로드 억제·순차 처리를 지원합니다. 알림 권한을 거부해도 앱 화면에서 진행 상태를 확인할 수 있습니다. 프로세스가 종료되어 중단된 작업은 앱에서 주소를 다시 제출할 수 있습니다.

## 화질과 지원 범위

- API가 제공하는 화질 후보와 HLS master를 확인하고 해상도, 프레임률, 비트레이트 순으로 선택합니다. 동률이면 원본 표시 후보를 우선합니다.
- HLS fMP4/TS와 직접 MP4를 내려받아 재인코딩 없이 MP4로 저장합니다. 선택한 최고 화질이 실패하면 낮은 화질로 자동 변경하지 않습니다.
- 공개 다시보기와 개별 캐치 링크를 지원합니다. 로그인·별도 시청 권한 필요, 암호화, 라이브, 타임라인 불연속, 분리 오디오 rendition 등 현재 처리하지 못하는 형식은 오류로 안내합니다.
- Android 10(API29) 이상. 패키지: `com.shaterguy.soopdownloader`.

## 검증

11개 단위 테스트가 URL·화질 순위·실제 공개 master·최고 화질 실패 처리·타임라인을 검사합니다. Android 15 에뮬레이터는 실제 예시 캐치를 공유하여 640×1080 영상과 음성이 갤러리에 한 번 저장되고, 복구 시 정상 파일이 보존되는지 확인합니다.

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

## 정식 릴리즈와 업데이트

모든 완료된 업데이트는 정식 릴리즈로 제공합니다. 고정 패키지와 동일 영구 서명을 유지하고 `versionName`·`versionCode`를 증가시킵니다. 최초 서명 설정은 [RELEASE_SETUP.md](RELEASE_SETUP.md), 이후 작업 규칙은 [AGENTS.md](AGENTS.md)를 따릅니다.

현재 최초 버전 1.0.0은 구현되었으며 영구 서명 초기화가 남아 있습니다. `SOOP_SIGNING_PASSPHRASE` Secret과 암호화 키가 없으면 정식 발행을 차단합니다. unsigned CI 산출물은 설치용 정식 APK가 아닙니다.

최초 기능 검증: [Android 15 공유 다운로드 테스트·빌드 성공](https://github.com/shaterguy/soop-downloader-android/actions/runs/34700629715/job/103571631443). 11개 단위 테스트 및 실제 캐치 다운로드·영상/음성·640×1080·갤러리 공개 검증을 통과했습니다. 같은 실행의 서명 job은 최초 Secret 미등록으로 의도대로 발행을 차단했습니다.

