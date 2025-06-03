# intra
---
```
filesharing-app/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── filesharing/
│   │   │       ├── main/
│   │   │       │   ├── App.java (가정: JavaFX 애플리케이션 진입점, 제공되지 않음)
│   │   │       │   ├── DatabaseManager.java (데이터베이스 관리, 로그 기록)
│   │   │       │   ├── SecurityManager.java (보안, SSL, 서명 검증)
│   │   │       │   ├── SettingsTab.java (설정 UI, 업데이트 관리)
│   │   │       │   ├── UIManager.java (메인 UI, 알림 배너)
│   │   │       │   ├── DeviceManager.java (가정: 장치 발견, 제공되지 않음)
│   │   │       │   ├── FileTransferManager.java (가정: 파일 전송, 제공되지 않음)
│   │   │       │   ├── ChatManager.java (가정: 채팅 관리, 제공되지 않음)
│   │   │       │   ├── StatsManager.java (가정: 통계 대시보드, 제공되지 않음)
│   │   │       └── settings/
│   │   │           ├── SupportedLanguage.java (언어 설정 열거형)
│   │   │           ├── SupportedTheme.java (테마 설정 열거형)
│   │   ├── resources/
│   │       ├── messages.properties (다국어 리소스 번들)
│   │       ├── dark.css (다크 테마 CSS)
│   │       ├── style.css (기본 테마 CSS)
│   │       ├── notification.wav (알림 사운드 파일)
│   ├── test/
│       └── (테스트 코드, 제공되지 않음)
├── keystore.jks (키스토어 파일, 공개키/인증서 저장)
├── transfers.db (SQLite: 파일 전송 로그)
├── chats.db (SQLite: 채팅 로그)
├── downloads.db (SQLite: 다운로드 로그)
├── activities.db (SQLite: 활동 로그)
├── versions.db (SQLite: 파일 버전 로그)
├── pom.xml (Maven 빌드 설정)
├── system.jar (현재 애플리케이션 JAR, 가정)
├── backup.jar (업데이트 롤백용 백업 JAR, 가정)
├── system_new.jar (업데이트 적용 후 새 JAR, 가정)
```
