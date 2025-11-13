# 게시판 기능 요구사항 및 설계

## 1. 데이터베이스 스키마 설계

### 1.1 posts 테이블
```sql
CREATE TABLE posts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(255) NOT NULL,
  content TEXT NOT NULL,
  author_id BIGINT NOT NULL,
  author_name VARCHAR(100) NOT NULL,
  view_count INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL,

  INDEX idx_author_id (author_id),
  INDEX idx_created_at (created_at DESC),
  INDEX idx_deleted_at (deleted_at)
);
```

### 1.2 스키마 설계 근거
- **id**: 게시글 고유 식별자 (BIGINT로 대용량 데이터 지원)
- **title**: 게시글 제목 (최대 255자)
- **content**: 게시글 본문 (TEXT 타입으로 긴 내용 지원)
- **author_id**: 작성자 식별자 (외부 사용자 시스템과 연동)
- **author_name**: 작성자 이름 (조회 성능 최적화를 위한 비정규화)
- **view_count**: 조회수 (게시글 인기도 측정)
- **created_at**: 생성 시간
- **updated_at**: 수정 시간
- **deleted_at**: 소프트 삭제를 위한 삭제 시간 (NULL = 활성 상태)

### 1.3 인덱스 전략
- `idx_author_id`: 특정 사용자의 게시글 조회 최적화
- `idx_created_at`: 최신순 정렬 조회 최적화
- `idx_deleted_at`: 삭제되지 않은 게시글 필터링 최적화

---

## 2. REST API 엔드포인트 설계

### 2.1 게시글 작성 (Create)
```
POST /api/posts
Content-Type: application/json

Request Body:
{
  "title": "게시글 제목",
  "content": "게시글 내용",
  "authorId": 123,
  "authorName": "홍길동"
}

Response (201 Created):
{
  "success": true,
  "data": {
    "id": 1,
    "title": "게시글 제목",
    "content": "게시글 내용",
    "authorId": 123,
    "authorName": "홍길동",
    "viewCount": 0,
    "createdAt": "2025-11-13T10:30:00Z",
    "updatedAt": "2025-11-13T10:30:00Z"
  }
}

Error Response (400 Bad Request):
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "제목은 필수 입력 항목입니다"
  }
}
```

### 2.2 게시글 목록 조회 (Read - List)
```
GET /api/posts?page=1&size=20&sort=createdAt&order=desc

Query Parameters:
- page: 페이지 번호 (기본값: 1)
- size: 페이지 크기 (기본값: 20, 최대: 100)
- sort: 정렬 기준 (createdAt, viewCount, updatedAt)
- order: 정렬 순서 (asc, desc)
- authorId: 작성자 필터 (선택)

Response (200 OK):
{
  "success": true,
  "data": {
    "posts": [
      {
        "id": 1,
        "title": "게시글 제목",
        "authorName": "홍길동",
        "viewCount": 42,
        "createdAt": "2025-11-13T10:30:00Z"
      }
    ],
    "pagination": {
      "currentPage": 1,
      "pageSize": 20,
      "totalItems": 150,
      "totalPages": 8,
      "hasNext": true,
      "hasPrev": false
    }
  }
}
```

### 2.3 게시글 상세 조회 (Read - Detail)
```
GET /api/posts/:id

Response (200 OK):
{
  "success": true,
  "data": {
    "id": 1,
    "title": "게시글 제목",
    "content": "게시글 내용",
    "authorId": 123,
    "authorName": "홍길동",
    "viewCount": 43,
    "createdAt": "2025-11-13T10:30:00Z",
    "updatedAt": "2025-11-13T10:30:00Z"
  }
}

Error Response (404 Not Found):
{
  "success": false,
  "error": {
    "code": "POST_NOT_FOUND",
    "message": "게시글을 찾을 수 없습니다"
  }
}
```

### 2.4 게시글 수정 (Update)
```
PUT /api/posts/:id
Content-Type: application/json

Request Body:
{
  "title": "수정된 제목",
  "content": "수정된 내용",
  "authorId": 123  // 권한 검증용
}

Response (200 OK):
{
  "success": true,
  "data": {
    "id": 1,
    "title": "수정된 제목",
    "content": "수정된 내용",
    "authorId": 123,
    "authorName": "홍길동",
    "viewCount": 43,
    "createdAt": "2025-11-13T10:30:00Z",
    "updatedAt": "2025-11-13T11:00:00Z"
  }
}

Error Response (403 Forbidden):
{
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "게시글을 수정할 권한이 없습니다"
  }
}
```

### 2.5 게시글 삭제 (Delete)
```
DELETE /api/posts/:id
Content-Type: application/json

Request Body:
{
  "authorId": 123  // 권한 검증용
}

Response (200 OK):
{
  "success": true,
  "message": "게시글이 삭제되었습니다"
}

Error Response (403 Forbidden):
{
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "게시글을 삭제할 권한이 없습니다"
  }
}
```

---

## 3. 주요 비즈니스 로직

### 3.1 게시글 작성 로직
```
1. 입력 데이터 검증
   - 제목: 필수, 1-255자
   - 내용: 필수, 1-65535자
   - authorId: 필수, 양수
   - authorName: 필수, 1-100자

2. 데이터 저장
   - created_at, updated_at 자동 설정
   - view_count 초기값 0 설정

3. 응답 생성
   - 생성된 게시글 정보 반환
```

### 3.2 게시글 목록 조회 로직
```
1. 쿼리 파라미터 검증
   - page: 1 이상의 정수
   - size: 1-100 사이의 정수
   - sort: 허용된 필드만 사용
   - order: asc 또는 desc

2. 데이터베이스 쿼리 구성
   - WHERE deleted_at IS NULL (소프트 삭제 제외)
   - WHERE author_id = ? (작성자 필터링, 선택)
   - ORDER BY {sort} {order}
   - LIMIT {size} OFFSET {(page-1) * size}

3. 페이지네이션 정보 계산
   - totalItems: 전체 게시글 수
   - totalPages: ceil(totalItems / size)
   - hasNext: currentPage < totalPages
   - hasPrev: currentPage > 1

4. 응답 생성
   - 목록 형태로 간략한 정보만 반환 (content 제외)
```

### 3.3 게시글 상세 조회 로직
```
1. 게시글 존재 여부 확인
   - WHERE id = ? AND deleted_at IS NULL
   - 없으면 404 에러 반환

2. 조회수 증가
   - UPDATE posts SET view_count = view_count + 1
   - 동시성 제어를 위해 원자적 증가 연산 사용

3. 응답 생성
   - 전체 게시글 정보 반환 (content 포함)
```

### 3.4 게시글 수정 로직
```
1. 입력 데이터 검증
   - 게시글 작성 로직과 동일한 검증

2. 게시글 존재 및 권한 확인
   - WHERE id = ? AND deleted_at IS NULL
   - author_id와 요청자의 authorId 일치 확인
   - 불일치 시 403 에러 반환

3. 데이터 업데이트
   - title, content 업데이트
   - updated_at 자동 갱신

4. 응답 생성
   - 수정된 게시글 정보 반환
```

### 3.5 게시글 삭제 로직
```
1. 게시글 존재 및 권한 확인
   - WHERE id = ? AND deleted_at IS NULL
   - author_id와 요청자의 authorId 일치 확인
   - 불일치 시 403 에러 반환

2. 소프트 삭제 수행
   - UPDATE posts SET deleted_at = CURRENT_TIMESTAMP
   - 물리적 삭제 대신 논리적 삭제로 데이터 복구 가능

3. 응답 생성
   - 삭제 성공 메시지 반환
```

---

## 4. 추가 고려사항

### 4.1 보안
- **XSS 방지**: HTML 태그 이스케이프 처리
- **SQL Injection 방지**: Prepared Statement 사용
- **권한 검증**: 모든 수정/삭제 요청에서 작성자 확인
- **Rate Limiting**: API 요청 빈도 제한으로 남용 방지

### 4.2 성능 최적화
- **인덱스 활용**: 자주 사용되는 조회 조건에 인덱스 설정
- **페이지네이션**: 대용량 데이터 조회 시 성능 보장
- **캐싱**: 인기 게시글 캐싱으로 DB 부하 감소
- **읽기/쓰기 분리**: 조회 트래픽이 많을 경우 Read Replica 활용

### 4.3 확장 가능성
- **첨부파일**: 추가 테이블로 파일 관리
- **댓글**: comments 테이블 추가
- **좋아요/싫어요**: post_reactions 테이블 추가
- **카테고리**: categories 테이블 및 FK 추가
- **검색**: Elasticsearch 등 검색 엔진 통합

### 4.4 에러 처리
- **일관된 에러 응답**: 모든 API에서 동일한 에러 형식 사용
- **상세한 에러 코드**: 클라이언트가 에러 처리 가능하도록 구체적인 코드 제공
- **로깅**: 모든 에러 상황 로깅으로 모니터링 및 디버깅 지원

### 4.5 데이터 정합성
- **트랜잭션**: 여러 테이블 업데이트 시 ACID 보장
- **외래키 제약**: 필요 시 users 테이블과 FK 설정
- **NOT NULL 제약**: 필수 필드 데이터 무결성 보장
- **길이 제한**: 애플리케이션/DB 레벨 모두에서 검증

---

## 5. 보강된 요구사항

### 5.1 인증 및 인가 (Authentication & Authorization)
- **인증 방식**: JWT (JSON Web Token) Bearer Token 사용
- **인증 헤더**: `Authorization: Bearer <token>`
- **프로세스**:
  1. 사용자는 로그인 API를 통해 JWT 토큰 발급.
  2. 게시글 작성/수정/삭제 등 인증이 필요한 요청 시, HTTP 헤더에 JWT 포함.
  3. 서버는 토큰의 유효성을 검증하고, 토큰에서 `userId`를 추출하여 요청자의 `authorId`로 사용.
- **API 별 권한**:
  - **게시글 작성 (`POST /api/posts`)**: 인증된 모든 사용자.
  - **게시글 목록/상세 조회 (`GET /api/posts`)**: 인증 불필요 (공개).
  - **게시글 수정/삭제 (`PUT/DELETE /api/posts/:id`)**: 게시글 작성자 본인만 가능 (`authorId` 일치 여부 확인).
- **에러 응답**:
  - **401 Unauthorized**: 유효하지 않은 토큰 또는 토큰이 없는 경우.
  - **403 Forbidden**: 권한이 없는 경우 (예: 타인의 게시글 수정 시도).

### 5.2 조회수 중복 방지 및 최적화
- **문제**: 동일 사용자의 반복적인 조회로 인한 조회수 부풀림 및 DB Write 부하.
- **해결 방안 (세션 기반)**:
  1. 사용자가 특정 게시글을 조회하면, 서버는 사용자의 세션 또는 쿠키에 `viewed_posts`와 같은 형태로 조회한 게시글의 ID를 저장 (예: `viewed_posts: [1, 5, 10]`).
  2. 다음 조회 요청 시, 해당 게시글 ID가 세션/쿠키에 이미 존재하는지 확인.
  3. 존재하지 않을 경우에만 `view_count`를 1 증가시키고, 세션/쿠키에 ID를 추가.
  4. 세션/쿠키의 만료 시간은 24시간으로 설정하여, 24시간 내에는 동일 사용자의 조회수가 중복으로 증가하지 않도록 보장.
- **대안 (비동기 배치 업데이트)**:
  - 실시간 정확도가 덜 중요한 경우, Redis와 같은 인메모리 저장소에 조회 기록을 캐시.
  - 주기적인 배치(Batch) 작업을 통해 조회수를 DB에 일괄 업데이트하여 Write 부하 감소.

### 5.3 동시성 제어 (Concurrency Control)
- **문제**: 여러 사용자가 동시에 같은 게시글을 수정하거나 조회수를 올릴 때 발생하는 경쟁 상태(Race Condition).
- **해결 방안 (낙관적 잠금 - Optimistic Locking)**:
  1. `posts` 테이블에 `version` 필드(INTEGER 타입, 기본값 1)를 추가.
  2. **조회수 증가**: `UPDATE posts SET view_count = view_count + 1 WHERE id = ?`는 원자적 연산이므로 충돌 문제에서 비교적 자유로움.
  3. **게시글 수정**:
     - 클라이언트가 수정할 데이터를 조회할 때 `version` 값을 함께 가져옴.
     - 수정 요청 시, `UPDATE posts SET ..., version = version + 1 WHERE id = ? AND version = ?` 쿼리를 사용.
     - 만약 다른 사용자가 먼저 수정하여 `version`이 변경되었다면, UPDATE는 0개의 row를 반환.
     - 이 경우, 서버는 **409 Conflict** 에러를 반환하여 클라이언트에게 데이터가 변경되었음을 알리고, 재시도(데이터 다시 읽기 -> 수정)하도록 유도.

### 5.4 추가 API 명세

#### 5.4.1 게시글 검색
```
GET /api/posts/search?q=검색어&field=title_content&page=1&size=20

Query Parameters:
- q: 검색어 (필수)
- field: 검색 필드 (title, content, title_content, authorName) (기본값: title_content)

Response (200 OK):
{
  "success": true,
  "data": {
    "posts": [...],
    "pagination": {...},
    "query": "검색어",
    "matchCount": 42
  }
}
```

#### 5.4.2 게시글 일괄 삭제 (선택적)
```
DELETE /api/posts/bulk
Content-Type: application/json

Request Body:
{
  "postIds": [1, 5, 10],
  "authorId": 123 // 모든 postIds의 작성자가 동일해야 함
}

Response (200 OK):
{
  "success": true,
  "message": "3개의 게시글이 삭제되었습니다."
}
```

### 5.5 성능 목표 및 서비스 수준 목표 (SLO)
- **응답 시간 목표 (p95)**:
  - **목록/상세 조회**: `p95 < 200ms`
  - **게시글 작성**: `p95 < 300ms`
- **부하 가정**:
  - **동시 접속자 수 (CCU)**: 1,000명
  - **총 게시글 수**: 100만 건
  - **일일 조회 수**: 10만 건/일
  - **일일 작성 수**: 1만 건/일

### 5.6 상세 데이터 유효성 검증 규칙
- **title**:
  - `min_length`: 1
  - `max_length`: 255
  - `pattern`: `^[\\s\\S]{1,255}$`
  - `sanitization`: HTML 이스케이프 처리
- **content**:
  - `min_length`: 1
  - `max_length`: 65535
  - `sanitization`: XSS 방지를 위해 `DOMPurify`와 같은 라이브러리를 사용해 안전한 HTML 태그만 허용하거나, 전체 이스케이프 처리.
- **author_name**:
  - `pattern`: `^[가-힣a-zA-Z0-9\\s]{1,100}$` (특수문자 제외)

### 5.1 인증 및 인가 (Authentication & Authorization)
- **인증 방식**: JWT (JSON Web Token) Bearer Token 사용
- **인증 헤더**: `Authorization: Bearer <token>`
- **프로세스**:
  1. 사용자는 로그인 API를 통해 JWT 토큰 발급.
  2. 게시글 작성/수정/삭제 등 인증이 필요한 요청 시, HTTP 헤더에 JWT 포함.
  3. 서버는 토큰의 유효성을 검증하고, 토큰에서 `userId`를 추출하여 요청자의 `authorId`로 사용.
- **API 별 권한**:
  - **게시글 작성 (`POST /api/posts`)**: 인증된 모든 사용자.
  - **게시글 목록/상세 조회 (`GET /api/posts`)**: 인증 불필요 (공개).
  - **게시글 수정/삭제 (`PUT/DELETE /api/posts/:id`)**: 게시글 작성자 본인만 가능 (`authorId` 일치 여부 확인).
- **에러 응답**:
  - **401 Unauthorized**: 유효하지 않은 토큰 또는 토큰이 없는 경우.
  - **403 Forbidden**: 권한이 없는 경우 (예: 타인의 게시글 수정 시도).

### 5.2 조회수 중복 방지 및 최적화
- **문제**: 동일 사용자의 반복적인 조회로 인한 조회수 부풀림 및 DB Write 부하.
- **해결 방안 (세션 기반)**:
  1. 사용자가 특정 게시글을 조회하면, 서버는 사용자의 세션 또는 쿠키에 `viewed_posts`와 같은 형태로 조회한 게시글의 ID를 저장 (예: `viewed_posts: [1, 5, 10]`).
  2. 다음 조회 요청 시, 해당 게시글 ID가 세션/쿠키에 이미 존재하는지 확인.
  3. 존재하지 않을 경우에만 `view_count`를 1 증가시키고, 세션/쿠키에 ID를 추가.
  4. 세션/쿠키의 만료 시간은 24시간으로 설정하여, 24시간 내에는 동일 사용자의 조회수가 중복으로 증가하지 않도록 보장.
- **대안 (비동기 배치 업데이트)**:
  - 실시간 정확도가 덜 중요한 경우, Redis와 같은 인메모리 저장소에 조회 기록을 캐시.
  - 주기적인 배치(Batch) 작업을 통해 조회수를 DB에 일괄 업데이트하여 Write 부하 감소.

### 5.3 동시성 제어 (Concurrency Control)
- **문제**: 여러 사용자가 동시에 같은 게시글을 수정하거나 조회수를 올릴 때 발생하는 경쟁 상태(Race Condition).
- **해결 방안 (낙관적 잠금 - Optimistic Locking)**:
  1. `posts` 테이블에 `version` 필드(INTEGER 타입, 기본값 1)를 추가.
  2. **조회수 증가**: `UPDATE posts SET view_count = view_count + 1 WHERE id = ?`는 원자적 연산이므로 충돌 문제에서 비교적 자유로움.
  3. **게시글 수정**:
     - 클라이언트가 수정할 데이터를 조회할 때 `version` 값을 함께 가져옴.
     - 수정 요청 시, `UPDATE posts SET ..., version = version + 1 WHERE id = ? AND version = ?` 쿼리를 사용.
     - 만약 다른 사용자가 먼저 수정하여 `version`이 변경되었다면, UPDATE는 0개의 row를 반환.
     - 이 경우, 서버는 **409 Conflict** 에러를 반환하여 클라이언트에게 데이터가 변경되었음을 알리고, 재시도(데이터 다시 읽기 -> 수정)하도록 유도.

### 5.4 추가 API 명세

#### 5.4.1 게시글 검색
```
GET /api/posts/search?q=검색어&field=title_content&page=1&size=20

Query Parameters:
- q: 검색어 (필수)
- field: 검색 필드 (title, content, title_content, authorName) (기본값: title_content)

Response (200 OK):
{
  "success": true,
  "data": {
    "posts": [...],
    "pagination": {...},
    "query": "검색어",
    "matchCount": 42
  }
}
```

#### 5.4.2 게시글 일괄 삭제 (선택적)
```
DELETE /api/posts/bulk
Content-Type: application/json

Request Body:
{
  "postIds": [1, 5, 10],
  "authorId": 123 // 모든 postIds의 작성자가 동일해야 함
}

Response (200 OK):
{
  "success": true,
  "message": "3개의 게시글이 삭제되었습니다."
}
```

### 5.5 성능 목표 및 서비스 수준 목표 (SLO)
- **응답 시간 목표 (p95)**:
  - **목록/상세 조회**: `p95 < 200ms`
  - **게시글 작성**: `p95 < 300ms`
- **부하 가정**:
  - **동시 접속자 수 (CCU)**: 1,000명
  - **총 게시글 수**: 100만 건
  - **일일 조회 수**: 10만 건/일
  - **일일 작성 수**: 1만 건/일

### 5.6 상세 데이터 유효성 검증 규칙
- **title**:
  - `min_length`: 1
  - `max_length`: 255
  - `pattern`: `^[\\s\\S]{1,255}$`
  - `sanitization`: HTML 이스케이프 처리
- **content**:
  - `min_length`: 1
  - `max_length`: 65535
  - `sanitization`: XSS 방지를 위해 `DOMPurify`와 같은 라이브러리를 사용해 안전한 HTML 태그만 허용하거나, 전체 이스케이프 처리.
- **author_name**:
  - `pattern`: `^[가-힣a-zA-Z0-9\\s]{1,100}$` (특수문자 제외)
