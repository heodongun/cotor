# 게시판 기능 요구사항 및 설계 명세서

## 1. 개요
기본적인 CRUD(Create, Read, Update, Delete) 기능을 제공하는 게시판 시스템 설계

**핵심 기능:**
- ✅ 게시글 작성 (Create)
- ✅ 게시글 목록 조회 (Read - List)
- ✅ 게시글 상세 조회 (Read - Detail)
- ✅ 게시글 수정 (Update)
- ✅ 게시글 삭제 (Delete)

---

## 2. 데이터베이스 스키마 설계

### 2.1 posts 테이블

```sql
CREATE TABLE posts (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    title           VARCHAR(255)    NOT NULL,
    content         TEXT            NOT NULL,
    author_id       BIGINT          NOT NULL,
    author_name     VARCHAR(100)    NOT NULL,
    view_count      INT             DEFAULT 0,
    version         INT             DEFAULT 1,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP       NULL,

    INDEX idx_author_id (author_id),
    INDEX idx_created_at (created_at DESC),
    INDEX idx_deleted_at (deleted_at),
    INDEX idx_view_count (view_count DESC)
);
```

### 2.2 필드 설명

| 필드명 | 타입 | 설명 | 제약조건 |
|--------|------|------|----------|
| id | BIGINT | 게시글 고유 식별자 | PK, AUTO_INCREMENT |
| title | VARCHAR(255) | 게시글 제목 | NOT NULL, 1-255자 |
| content | TEXT | 게시글 본문 (최대 65,535자) | NOT NULL |
| author_id | BIGINT | 작성자 식별자 (외부 사용자 시스템 연동) | NOT NULL |
| author_name | VARCHAR(100) | 작성자 이름 (비정규화, 조회 성능 최적화) | NOT NULL |
| view_count | INT | 조회수 | DEFAULT 0 |
| version | INT | 낙관적 잠금을 위한 버전 번호 | DEFAULT 1 |
| created_at | TIMESTAMP | 생성 일시 | 자동 생성 |
| updated_at | TIMESTAMP | 수정 일시 | 자동 업데이트 |
| deleted_at | TIMESTAMP | 삭제 일시 (소프트 삭제) | NULL = 활성 상태 |

### 2.3 인덱스 전략

- **idx_author_id**: 특정 작성자의 게시글 조회 최적화
- **idx_created_at**: 최신순 정렬 조회 최적화 (DESC)
- **idx_deleted_at**: 활성 게시글 필터링 최적화
- **idx_view_count**: 인기순 정렬 조회 최적화 (DESC)

### 2.4 스키마 설계 근거

1. **소프트 삭제 (deleted_at)**: 데이터 복구 가능, 감사 추적, 참조 무결성 보존
2. **비정규화 (author_name)**: JOIN 없이 목록 조회 성능 향상
3. **version 필드**: 동시 수정 시 충돌 감지 (낙관적 잠금)
4. **BIGINT id**: 대규모 데이터 지원 (최대 약 922경 레코드)

---

## 3. REST API 엔드포인트 설계

### 3.1 게시글 작성 (Create)

```http
POST /api/posts
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**Request Body:**
```json
{
  "title": "게시글 제목",
  "content": "게시글 내용",
  "authorId": 123,
  "authorName": "홍길동"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "title": "게시글 제목",
    "content": "게시글 내용",
    "authorId": 123,
    "authorName": "홍길동",
    "viewCount": 0,
    "version": 1,
    "createdAt": "2025-11-13T10:30:00Z",
    "updatedAt": "2025-11-13T10:30:00Z"
  }
}
```

**Validation Rules:**
- `title`: 필수, 1-255자, HTML 이스케이프
- `content`: 필수, 1-65535자, XSS 방지 처리
- `authorId`: 필수, 양수, JWT 토큰에서 추출
- `authorName`: 필수, 1-100자, 패턴: `^[가-힣a-zA-Z0-9\s]{1,100}$`

**Error Responses:**
```json
// 400 Bad Request - Validation 실패
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "제목은 필수 입력 항목입니다",
    "details": {
      "field": "title",
      "constraint": "required"
    }
  }
}

// 401 Unauthorized - 인증 실패
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "유효하지 않은 인증 토큰입니다"
  }
}
```

---

### 3.2 게시글 목록 조회 (Read - List)

```http
GET /api/posts?page=1&size=20&sort=createdAt&order=desc&authorId=123
```

**Query Parameters:**

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| page | int | 1 | 페이지 번호 (1부터 시작) |
| size | int | 20 | 페이지 크기 (1-100) |
| sort | string | createdAt | 정렬 기준 (createdAt, viewCount, updatedAt) |
| order | string | desc | 정렬 순서 (asc, desc) |
| authorId | int | - | 작성자 필터 (선택) |

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "posts": [
      {
        "id": 2,
        "title": "두 번째 게시글",
        "authorId": 123,
        "authorName": "홍길동",
        "viewCount": 42,
        "createdAt": "2025-11-13T11:00:00Z",
        "updatedAt": "2025-11-13T11:00:00Z"
      },
      {
        "id": 1,
        "title": "첫 번째 게시글",
        "authorId": 123,
        "authorName": "홍길동",
        "viewCount": 15,
        "createdAt": "2025-11-13T10:30:00Z",
        "updatedAt": "2025-11-13T10:30:00Z"
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

**특징:**
- 목록 조회 시 `content` 필드 제외 (성능 최적화)
- 삭제된 게시글(`deleted_at IS NOT NULL`) 자동 필터링
- 인증 불필요 (공개 조회)

---

### 3.3 게시글 상세 조회 (Read - Detail)

```http
GET /api/posts/{id}
```

**Path Parameters:**
- `id`: 게시글 ID (BIGINT)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "title": "게시글 제목",
    "content": "게시글 본문 내용",
    "authorId": 123,
    "authorName": "홍길동",
    "viewCount": 43,
    "version": 1,
    "createdAt": "2025-11-13T10:30:00Z",
    "updatedAt": "2025-11-13T10:30:00Z"
  }
}
```

**Response (404 Not Found):**
```json
{
  "success": false,
  "error": {
    "code": "POST_NOT_FOUND",
    "message": "게시글을 찾을 수 없습니다"
  }
}
```

**특징:**
- 조회 시 `view_count` 자동 증가 (원자적 연산)
- 전체 필드 반환 (`content` 포함)
- 인증 불필요 (공개 조회)

---

### 3.4 게시글 수정 (Update)

```http
PUT /api/posts/{id}
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**Path Parameters:**
- `id`: 게시글 ID (BIGINT)

**Request Body:**
```json
{
  "title": "수정된 제목",
  "content": "수정된 내용",
  "version": 1
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "title": "수정된 제목",
    "content": "수정된 내용",
    "authorId": 123,
    "authorName": "홍길동",
    "viewCount": 43,
    "version": 2,
    "createdAt": "2025-11-13T10:30:00Z",
    "updatedAt": "2025-11-13T12:00:00Z"
  }
}
```

**Error Responses:**
```json
// 403 Forbidden - 권한 없음
{
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "게시글을 수정할 권한이 없습니다"
  }
}

// 409 Conflict - 버전 충돌
{
  "success": false,
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "게시글이 다른 사용자에 의해 수정되었습니다. 다시 조회 후 수정해주세요.",
    "details": {
      "currentVersion": 2,
      "providedVersion": 1
    }
  }
}
```

**특징:**
- JWT 토큰에서 추출한 `authorId`와 게시글의 `author_id` 일치 확인
- 낙관적 잠금: `version` 필드로 동시 수정 충돌 감지
- 수정 성공 시 `version` 자동 증가

---

### 3.5 게시글 삭제 (Delete)

```http
DELETE /api/posts/{id}
Authorization: Bearer <JWT_TOKEN>
```

**Path Parameters:**
- `id`: 게시글 ID (BIGINT)

**Response (200 OK):**
```json
{
  "success": true,
  "message": "게시글이 삭제되었습니다"
}
```

**Error Responses:**
```json
// 403 Forbidden - 권한 없음
{
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "게시글을 삭제할 권한이 없습니다"
  }
}

// 404 Not Found - 게시글 없음
{
  "success": false,
  "error": {
    "code": "POST_NOT_FOUND",
    "message": "게시글을 찾을 수 없습니다"
  }
}
```

**특징:**
- 소프트 삭제: `deleted_at` 필드에 현재 시간 설정
- 물리적 삭제 없음 (데이터 복구 가능)
- JWT 토큰에서 추출한 `authorId`와 일치 확인

---

### 3.6 게시글 검색 (Search)

```http
GET /api/posts/search?q=검색어&field=title_content&page=1&size=20
```

**Query Parameters:**

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| q | string | - | 검색어 (필수) |
| field | string | title_content | 검색 필드 (title, content, title_content, authorName) |
| page | int | 1 | 페이지 번호 |
| size | int | 20 | 페이지 크기 |

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "posts": [
      {
        "id": 5,
        "title": "검색어가 포함된 제목",
        "authorName": "홍길동",
        "viewCount": 10,
        "createdAt": "2025-11-13T10:30:00Z"
      }
    ],
    "pagination": {
      "currentPage": 1,
      "pageSize": 20,
      "totalItems": 42,
      "totalPages": 3,
      "hasNext": true,
      "hasPrev": false
    },
    "query": "검색어",
    "matchCount": 42
  }
}
```

---

### 3.7 게시글 일괄 삭제 (Bulk Delete)

```http
DELETE /api/posts/bulk
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**Request Body:**
```json
{
  "postIds": [1, 5, 10]
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "message": "3개의 게시글이 삭제되었습니다",
  "deletedCount": 3
}
```

**Error Response (403 Forbidden):**
```json
{
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "일부 게시글에 대한 삭제 권한이 없습니다",
    "details": {
      "deniedPostIds": [5, 10]
    }
  }
}
```

---

## 4. 주요 비즈니스 로직

### 4.1 게시글 작성 로직

```
1. 인증 확인
   - JWT 토큰 유효성 검증
   - 토큰에서 userId 추출 → authorId로 사용

2. 입력 데이터 검증
   - title: 필수, 1-255자
   - content: 필수, 1-65535자
   - authorName: 1-100자, 패턴 검증
   - HTML/XSS 방지 처리

3. 데이터 저장
   - created_at, updated_at 자동 설정
   - view_count = 0, version = 1 초기화
   - deleted_at = NULL

4. 응답 생성
   - 생성된 게시글 정보 반환
   - HTTP 201 Created
```

**SQL 예시:**
```sql
INSERT INTO posts (title, content, author_id, author_name, view_count, version)
VALUES (?, ?, ?, ?, 0, 1);
```

---

### 4.2 게시글 목록 조회 로직

```
1. 쿼리 파라미터 검증
   - page: 1 이상의 정수
   - size: 1-100 사이의 정수
   - sort: 허용된 필드만 (createdAt, viewCount, updatedAt)
   - order: asc 또는 desc

2. 필터링 조건 구성
   - WHERE deleted_at IS NULL (소프트 삭제 제외)
   - WHERE author_id = ? (작성자 필터, 선택)

3. 데이터베이스 쿼리
   - ORDER BY {sort} {order}
   - LIMIT {size} OFFSET {(page-1) * size}
   - content 필드 제외 (성능 최적화)

4. 페이지네이션 정보 계산
   - totalItems: COUNT(*) WHERE deleted_at IS NULL
   - totalPages: ceil(totalItems / size)
   - hasNext: currentPage < totalPages
   - hasPrev: currentPage > 1

5. 응답 생성
   - 게시글 목록 + 페이지네이션 메타데이터
   - HTTP 200 OK
```

**SQL 예시:**
```sql
-- 게시글 목록 조회
SELECT id, title, author_id, author_name, view_count, created_at, updated_at
FROM posts
WHERE deleted_at IS NULL
  AND (author_id = ? OR ? IS NULL)
ORDER BY created_at DESC
LIMIT 20 OFFSET 0;

-- 전체 개수 조회
SELECT COUNT(*) as total
FROM posts
WHERE deleted_at IS NULL
  AND (author_id = ? OR ? IS NULL);
```

---

### 4.3 게시글 상세 조회 로직

```
1. 게시글 존재 여부 확인
   - WHERE id = ? AND deleted_at IS NULL
   - 없으면 404 Not Found 반환

2. 조회수 증가 (원자적 연산)
   - UPDATE posts SET view_count = view_count + 1
   - 트랜잭션 내에서 처리
   - 세션/쿠키 기반 중복 방지 (선택)

3. 전체 데이터 조회
   - 모든 필드 포함 (content 포함)

4. 응답 생성
   - 전체 게시글 정보 반환
   - HTTP 200 OK
```

**SQL 예시:**
```sql
-- 조회수 증가 (원자적 연산)
UPDATE posts
SET view_count = view_count + 1
WHERE id = ? AND deleted_at IS NULL;

-- 게시글 조회
SELECT *
FROM posts
WHERE id = ? AND deleted_at IS NULL;
```

**조회수 중복 방지 로직 (선택):**
```
1. 세션/쿠키에서 조회 이력 확인
   - Cookie: viewed_posts=[1, 5, 10]
   - 게시글 ID가 이미 존재하면 조회수 증가 스킵

2. 조회수 증가 시
   - view_count + 1 수행
   - 세션/쿠키에 게시글 ID 추가
   - 만료 시간: 24시간

3. 대안: Redis 캐시
   - Key: user:{userId}:viewed:{postId}
   - TTL: 24시간
   - 배치 업데이트로 DB 부하 감소
```

---

### 4.4 게시글 수정 로직

```
1. 인증 확인
   - JWT 토큰 유효성 검증
   - 토큰에서 userId 추출

2. 게시글 존재 및 권한 확인
   - WHERE id = ? AND deleted_at IS NULL
   - author_id와 요청자의 authorId 일치 확인
   - 불일치 시 403 Forbidden 반환

3. 버전 충돌 확인 (낙관적 잠금)
   - 요청의 version과 DB의 version 비교
   - 불일치 시 409 Conflict 반환

4. 입력 데이터 검증
   - title, content validation
   - HTML/XSS 방지 처리

5. 데이터 업데이트
   - title, content 업데이트
   - version = version + 1
   - updated_at 자동 갱신

6. 응답 생성
   - 수정된 게시글 정보 반환
   - HTTP 200 OK
```

**SQL 예시 (낙관적 잠금):**
```sql
UPDATE posts
SET
    title = ?,
    content = ?,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = ?
  AND author_id = ?
  AND version = ?
  AND deleted_at IS NULL;

-- 영향받은 행이 0이면 버전 충돌 또는 권한 없음
```

---

### 4.5 게시글 삭제 로직

```
1. 인증 확인
   - JWT 토큰 유효성 검증
   - 토큰에서 userId 추출

2. 게시글 존재 및 권한 확인
   - WHERE id = ? AND deleted_at IS NULL
   - author_id와 요청자의 authorId 일치 확인
   - 불일치 시 403 Forbidden 반환

3. 소프트 삭제 수행
   - deleted_at = CURRENT_TIMESTAMP
   - 물리적 삭제 없음 (데이터 보존)

4. 응답 생성
   - 삭제 성공 메시지 반환
   - HTTP 200 OK
```

**SQL 예시:**
```sql
UPDATE posts
SET deleted_at = CURRENT_TIMESTAMP
WHERE id = ?
  AND author_id = ?
  AND deleted_at IS NULL;
```

**소프트 삭제 장점:**
- ✅ 데이터 복구 가능
- ✅ 감사 추적 및 로그 유지
- ✅ 참조 무결성 보존
- ✅ 법적/규정 준수 (데이터 보관 정책)

---

### 4.6 게시글 검색 로직

```
1. 검색어 검증
   - q: 필수, 최소 1자
   - SQL Injection 방지 (Prepared Statement)

2. 검색 필드 결정
   - title: LIKE '%{query}%' ON title
   - content: LIKE '%{query}%' ON content
   - title_content: (title OR content)
   - authorName: LIKE '%{query}%' ON author_name

3. 전문 검색 인덱스 활용 (선택)
   - MySQL: FULLTEXT INDEX
   - PostgreSQL: tsvector, tsquery
   - Elasticsearch: 외부 검색 엔진

4. 페이지네이션 적용
   - LIMIT, OFFSET 사용

5. 응답 생성
   - 검색 결과 + 페이지네이션 + 매칭 개수
```

**SQL 예시 (LIKE 검색):**
```sql
SELECT id, title, author_name, view_count, created_at
FROM posts
WHERE deleted_at IS NULL
  AND (
    CASE
      WHEN ? = 'title' THEN title LIKE ?
      WHEN ? = 'content' THEN content LIKE ?
      WHEN ? = 'title_content' THEN (title LIKE ? OR content LIKE ?)
      WHEN ? = 'authorName' THEN author_name LIKE ?
    END
  )
ORDER BY created_at DESC
LIMIT 20 OFFSET 0;
```

---

## 5. 인증 및 인가 (Authentication & Authorization)

### 5.1 인증 방식

**JWT (JSON Web Token) Bearer Token 사용**

- **인증 헤더**: `Authorization: Bearer <JWT_TOKEN>`
- **토큰 구조**:
  ```json
  {
    "userId": 123,
    "username": "홍길동",
    "email": "hong@example.com",
    "iat": 1699876543,
    "exp": 1699962943
  }
  ```

### 5.2 인증 프로세스

```
1. 사용자 로그인
   - POST /api/auth/login
   - 이메일/비밀번호 검증
   - JWT 토큰 발급 및 반환

2. API 요청 시
   - HTTP 헤더에 JWT 포함
   - Authorization: Bearer <token>

3. 서버 검증
   - 토큰 유효성 검증 (서명, 만료 시간)
   - 토큰에서 userId 추출
   - 요청의 authorId로 사용
```

### 5.3 API별 권한 정책

| API | 인증 필요 | 권한 |
|-----|----------|------|
| POST /api/posts | ✅ 필수 | 인증된 모든 사용자 |
| GET /api/posts | ❌ 불필요 | 공개 |
| GET /api/posts/:id | ❌ 불필요 | 공개 |
| PUT /api/posts/:id | ✅ 필수 | 작성자 본인만 |
| DELETE /api/posts/:id | ✅ 필수 | 작성자 본인만 |
| GET /api/posts/search | ❌ 불필요 | 공개 |
| DELETE /api/posts/bulk | ✅ 필수 | 작성자 본인만 |

### 5.4 권한 검증 로직

```
1. 토큰에서 userId 추출
   - JWT payload의 userId 사용

2. 게시글 작성자 확인
   - SELECT author_id FROM posts WHERE id = ?
   - author_id와 userId 비교

3. 권한 판단
   - 일치: 수정/삭제 허용
   - 불일치: 403 Forbidden 반환
```

### 5.5 에러 응답

```json
// 401 Unauthorized - 토큰 없음/유효하지 않음
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "유효하지 않은 인증 토큰입니다"
  }
}

// 403 Forbidden - 권한 없음
{
  "success": false,
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "이 작업을 수행할 권한이 없습니다"
  }
}
```

---

## 6. 동시성 제어 (Concurrency Control)

### 6.1 문제 상황

여러 사용자가 동시에 같은 게시글을 수정할 때 발생하는 **경쟁 상태(Race Condition)**

**시나리오:**
```
사용자 A: 게시글 조회 (version: 1)
사용자 B: 게시글 조회 (version: 1)
사용자 A: 게시글 수정 (version 1 → 2)
사용자 B: 게시글 수정 (version 1 → 2?) ← 충돌 발생!
```

### 6.2 해결 방안: 낙관적 잠금 (Optimistic Locking)

**구현 방법:**

1. **version 필드 추가**
   - 테이블에 `version INT DEFAULT 1` 추가
   - 매 수정 시 version 증가

2. **수정 시 version 확인**
   ```sql
   UPDATE posts
   SET
       title = ?,
       content = ?,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
   WHERE id = ?
     AND version = ?
     AND deleted_at IS NULL;
   ```

3. **충돌 감지**
   - UPDATE가 0개의 row를 반환하면 버전 충돌
   - 409 Conflict 에러 반환
   - 클라이언트에게 재조회 요청

4. **클라이언트 처리**
   ```
   1. 게시글 조회 (version 포함)
   2. 수정 요청 (version 포함)
   3. 409 Conflict 수신
   4. 게시글 재조회 (최신 version)
   5. 수정 재시도
   ```

### 6.3 조회수 증가의 동시성

**문제:**
- 동시에 여러 사용자가 조회할 때 조회수 정확성

**해결:**
- 원자적 증가 연산 사용
  ```sql
  UPDATE posts
  SET view_count = view_count + 1
  WHERE id = ?;
  ```
- 단일 쿼리로 처리하여 경쟁 상태 방지

**추가 최적화 (선택):**
- Redis 캐시에 조회수 증가 이벤트 저장
- 배치 작업으로 주기적 DB 반영 (예: 1분마다)
- 실시간 정확도 vs DB 부하 트레이드오프

---

## 7. 보안 고려사항

### 7.1 SQL Injection 방지

- ✅ **Prepared Statement 사용**: 모든 DB 쿼리
- ✅ **ORM/Query Builder**: 안전한 쿼리 생성
- ❌ **문자열 연결 금지**: `"SELECT * FROM posts WHERE id = " + id`

### 7.2 XSS (Cross-Site Scripting) 방지

**입력 처리:**
- ✅ **HTML 이스케이프**: `<script>` → `&lt;script&gt;`
- ✅ **DOMPurify 사용**: 안전한 HTML만 허용
- ✅ **Content-Security-Policy 헤더**: 브라우저 레벨 보호

**출력 처리:**
- ✅ **템플릿 엔진 자동 이스케이프**: React, Vue 등
- ✅ **dangerouslySetInnerHTML 사용 제한**: 필요 시에만

### 7.3 CSRF (Cross-Site Request Forgery) 방지

- ✅ **CSRF 토큰**: POST/PUT/DELETE 요청에 포함
- ✅ **SameSite Cookie**: `SameSite=Strict` 설정
- ✅ **Origin/Referer 검증**: 요청 출처 확인

### 7.4 Rate Limiting (API 요청 제한)

**목적:**
- DDoS 공격 방지
- API 남용 방지
- 서버 부하 관리

**구현 방법:**
```
1. IP 기반 제한
   - 예: 1분당 60 요청

2. 사용자 기반 제한
   - 예: 1시간당 1000 요청

3. Redis 사용
   - Key: rate_limit:{userId/IP}
   - Value: 요청 카운트
   - TTL: 시간 윈도우

4. 초과 시 응답
   - HTTP 429 Too Many Requests
   - Retry-After 헤더 포함
```

### 7.5 민감 정보 보호

- ✅ **비밀번호 해싱**: bcrypt, Argon2 사용
- ✅ **JWT Secret 관리**: 환경 변수, 안전한 저장소
- ✅ **HTTPS 강제**: TLS/SSL 인증서
- ✅ **로그 마스킹**: 민감 정보 로그 제외

---

## 8. 성능 최적화

### 8.1 데이터베이스 최적화

**인덱스 전략:**
- ✅ 자주 조회되는 컬럼에 인덱스 (created_at, author_id, view_count)
- ✅ 복합 인덱스 고려 (deleted_at + created_at)
- ⚠️ 과도한 인덱스 주의 (INSERT/UPDATE 성능 저하)

**쿼리 최적화:**
- ✅ SELECT 필드 명시 (`SELECT *` 지양)
- ✅ 목록 조회 시 content 제외
- ✅ EXPLAIN으로 쿼리 플랜 분석
- ✅ N+1 문제 방지 (JOIN 또는 배치 조회)

### 8.2 캐싱 전략

**Redis 캐싱 예시:**

```yaml
# 게시글 상세 정보
Key: post:{id}
Value: JSON 직렬화된 게시글 데이터
TTL: 5분

# 게시글 목록 (첫 페이지)
Key: posts:list:page:1:sort:createdAt:order:desc
Value: JSON 배열
TTL: 1분

# 인기 게시글 (조회수 상위 10개)
Key: posts:popular:top10
Value: JSON 배열
TTL: 10분
```

**캐시 무효화 전략:**
- 게시글 작성/수정/삭제 시 관련 캐시 삭제
- `DEL post:{id}`
- `DEL posts:list:*` (패턴 매칭)

### 8.3 페이지네이션 최적화

**Offset 기반 페이지네이션 (현재 구현):**
```sql
SELECT * FROM posts
ORDER BY created_at DESC
LIMIT 20 OFFSET 100;
```
- ⚠️ Offset이 클수록 성능 저하
- ✅ 일반적인 사용에 적합

**Cursor 기반 페이지네이션 (대용량 최적화):**
```sql
SELECT * FROM posts
WHERE created_at < ?
  AND id < ?
ORDER BY created_at DESC, id DESC
LIMIT 20;
```
- ✅ 대용량 데이터에서 일정한 성능
- ✅ 무한 스크롤에 적합
- ⚠️ 특정 페이지 점프 불가

### 8.4 읽기/쓰기 분리 (Read Replica)

**구조:**
```
Master DB (쓰기 전용)
  ↓ Replication
Slave DB(s) (읽기 전용)
```

**적용 전략:**
- ✅ 조회 API → Slave DB
- ✅ 작성/수정/삭제 → Master DB
- ⚠️ Replication Lag 고려 (수 밀리초~수 초)

---

## 9. 성능 목표 및 SLO (Service Level Objectives)

### 9.1 응답 시간 목표

| API | p50 | p95 | p99 |
|-----|-----|-----|-----|
| GET /api/posts | < 50ms | < 200ms | < 500ms |
| GET /api/posts/:id | < 50ms | < 200ms | < 500ms |
| POST /api/posts | < 100ms | < 300ms | < 700ms |
| PUT /api/posts/:id | < 100ms | < 300ms | < 700ms |
| DELETE /api/posts/:id | < 50ms | < 200ms | < 500ms |

### 9.2 부하 가정

**사용자 지표:**
- 동시 접속자 수 (CCU): 1,000명
- 일일 활성 사용자 (DAU): 10,000명
- 월간 활성 사용자 (MAU): 100,000명

**데이터 지표:**
- 총 게시글 수: 1,000,000건
- 일일 신규 게시글: 10,000건
- 일일 조회 수: 100,000건

**트래픽 패턴:**
- 평균 RPS (Requests Per Second): 50 RPS
- 피크 RPS: 500 RPS (10배)
- 읽기:쓰기 비율: 9:1

### 9.3 가용성 목표

- **Uptime**: 99.9% (연간 약 8.76시간 다운타임)
- **장애 복구 시간 (MTTR)**: < 15분
- **데이터 손실 방지**: RPO (Recovery Point Objective) < 1시간

---

## 10. 모니터링 및 로깅

### 10.1 애플리케이션 메트릭

**핵심 지표:**
- ✅ API 응답 시간 (p50, p95, p99)
- ✅ 에러율 (5xx, 4xx)
- ✅ 처리량 (RPS)
- ✅ 동시 접속자 수

**비즈니스 지표:**
- ✅ 일일 게시글 작성 수
- ✅ 일일 조회 수
- ✅ 활성 사용자 수 (DAU, MAU)

### 10.2 데이터베이스 모니터링

- ✅ 쿼리 실행 시간
- ✅ Slow Query 로그 (> 1초)
- ✅ 커넥션 풀 사용률
- ✅ 인덱스 효율성

### 10.3 로깅 전략

**로그 레벨:**
```
ERROR: 시스템 오류, 즉시 대응 필요
WARN:  잠재적 문제, 모니터링 필요
INFO:  주요 이벤트 (로그인, 게시글 작성)
DEBUG: 개발/디버깅 정보
```

**로그 내용:**
```json
{
  "timestamp": "2025-11-13T10:30:00Z",
  "level": "INFO",
  "service": "board-api",
  "method": "POST",
  "path": "/api/posts",
  "userId": 123,
  "statusCode": 201,
  "responseTime": 150,
  "requestId": "abc123"
}
```

**로그 관리:**
- ✅ 구조화된 로그 (JSON 형식)
- ✅ 중앙 집중식 로그 수집 (ELK Stack, Datadog)
- ✅ 민감 정보 마스킹 (비밀번호, 토큰)
- ✅ 로그 보관 정책 (예: 30일)

---

## 11. 추가 확장 가능성

### 11.1 댓글 기능

**테이블 설계:**
```sql
CREATE TABLE comments (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id     BIGINT NOT NULL,
    parent_id   BIGINT NULL,
    author_id   BIGINT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP NULL,

    FOREIGN KEY (post_id) REFERENCES posts(id),
    INDEX idx_post_id (post_id)
);
```

### 11.2 좋아요/싫어요 기능

**테이블 설계:**
```sql
CREATE TABLE post_reactions (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id     BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    reaction    ENUM('like', 'dislike') NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_post_user (post_id, user_id),
    INDEX idx_post_id (post_id)
);
```

### 11.3 카테고리 분류

**테이블 설계:**
```sql
CREATE TABLE categories (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    name    VARCHAR(50) NOT NULL,
    slug    VARCHAR(50) UNIQUE NOT NULL
);

ALTER TABLE posts
ADD COLUMN category_id BIGINT NULL,
ADD FOREIGN KEY (category_id) REFERENCES categories(id);
```

### 11.4 첨부파일 지원

**테이블 설계:**
```sql
CREATE TABLE post_attachments (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id     BIGINT NOT NULL,
    filename    VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    file_size   BIGINT NOT NULL,
    mime_type   VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (post_id) REFERENCES posts(id)
);
```

### 11.5 태그 시스템

**테이블 설계:**
```sql
CREATE TABLE tags (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    name    VARCHAR(50) UNIQUE NOT NULL
);

CREATE TABLE post_tags (
    post_id BIGINT NOT NULL,
    tag_id  BIGINT NOT NULL,

    PRIMARY KEY (post_id, tag_id),
    FOREIGN KEY (post_id) REFERENCES posts(id),
    FOREIGN KEY (tag_id) REFERENCES tags(id)
);
```

### 11.6 전문 검색 (Elasticsearch)

**인덱스 매핑:**
```json
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "korean"
      },
      "content": {
        "type": "text",
        "analyzer": "korean"
      },
      "authorName": {
        "type": "keyword"
      },
      "createdAt": {
        "type": "date"
      }
    }
  }
}
```

---

## 12. 에러 코드 전체 목록

| HTTP 상태 | 에러 코드 | 메시지 | 설명 |
|-----------|-----------|--------|------|
| 400 | VALIDATION_ERROR | 입력 데이터가 유효하지 않습니다 | Validation 실패 |
| 400 | INVALID_PARAMETER | 잘못된 파라미터입니다 | 쿼리/경로 파라미터 오류 |
| 401 | UNAUTHORIZED | 인증이 필요합니다 | JWT 토큰 없음/만료 |
| 401 | INVALID_TOKEN | 유효하지 않은 토큰입니다 | JWT 검증 실패 |
| 403 | PERMISSION_DENIED | 권한이 없습니다 | 작성자 불일치 |
| 404 | POST_NOT_FOUND | 게시글을 찾을 수 없습니다 | 게시글 ID 존재 안 함 |
| 409 | VERSION_CONFLICT | 다른 사용자가 수정했습니다 | 버전 충돌 (낙관적 잠금) |
| 429 | RATE_LIMIT_EXCEEDED | 요청 한도를 초과했습니다 | Rate Limiting |
| 500 | INTERNAL_SERVER_ERROR | 서버 오류가 발생했습니다 | 서버 내부 오류 |
| 503 | SERVICE_UNAVAILABLE | 서비스를 사용할 수 없습니다 | 시스템 점검/장애 |

---

## 13. 구현 우선순위

### Phase 1: MVP (Minimum Viable Product)
**목표: 기본 CRUD 기능 구현 (2주)**

- ✅ 데이터베이스 테이블 생성
- ✅ CRUD API 구현 (생성, 조회, 수정, 삭제)
- ✅ 기본 Validation 및 에러 처리
- ✅ 페이지네이션
- ✅ 소프트 삭제
- ✅ 단위 테스트 작성

### Phase 2: 보안 및 안정성 (1주)
**목표: 인증/인가 및 동시성 제어**

- ✅ JWT 인증 구현
- ✅ 권한 검증 (작성자 확인)
- ✅ 낙관적 잠금 (version 필드)
- ✅ XSS, SQL Injection 방지
- ✅ Rate Limiting
- ✅ 통합 테스트

### Phase 3: 성능 최적화 (1주)
**목표: 대용량 트래픽 대비**

- ✅ 인덱스 최적화
- ✅ Redis 캐싱 도입
- ✅ 조회수 중복 방지 로직
- ✅ Slow Query 분석 및 개선
- ✅ 부하 테스트 (Locust, k6)

### Phase 4: 확장 기능 (2주)
**목표: 사용자 경험 개선**

- ✅ 게시글 검색 기능
- ✅ 댓글 시스템
- ✅ 좋아요/싫어요
- ✅ 카테고리 분류
- ✅ 첨부파일 업로드

### Phase 5: 운영 및 모니터링 (지속)
**목표: 안정적 서비스 운영**

- ✅ 로깅 시스템 구축
- ✅ 모니터링 대시보드 (Grafana)
- ✅ 알림 시스템 (Slack, PagerDuty)
- ✅ 장애 대응 매뉴얼
- ✅ 백업 및 복구 전략

---

## 14. 참고 자료

### 14.1 기술 스택 예시

**백엔드:**
- Node.js + Express / Nest.js
- Java + Spring Boot
- Python + FastAPI / Django
- Go + Gin / Fiber

**데이터베이스:**
- MySQL 8.0+
- PostgreSQL 14+
- MariaDB 10.6+

**캐싱:**
- Redis 7.0+
- Memcached

**검색:**
- Elasticsearch 8.0+
- OpenSearch

**인증:**
- JWT (jsonwebtoken)
- OAuth 2.0 (선택)

### 14.2 코드 예시 (Pseudo Code)

**게시글 작성 API (Node.js + Express):**
```javascript
router.post('/api/posts', authenticateJWT, async (req, res) => {
  try {
    // 1. Validation
    const { title, content } = req.body;
    if (!title || title.length > 255) {
      return res.status(400).json({
        success: false,
        error: { code: 'VALIDATION_ERROR', message: '제목은 1-255자여야 합니다' }
      });
    }

    // 2. XSS 방지
    const sanitizedTitle = escapeHtml(title);
    const sanitizedContent = purifyHtml(content);

    // 3. DB 저장
    const post = await db.query(
      'INSERT INTO posts (title, content, author_id, author_name) VALUES (?, ?, ?, ?)',
      [sanitizedTitle, sanitizedContent, req.user.id, req.user.name]
    );

    // 4. 응답
    res.status(201).json({
      success: true,
      data: {
        id: post.insertId,
        title: sanitizedTitle,
        content: sanitizedContent,
        authorId: req.user.id,
        authorName: req.user.name,
        viewCount: 0,
        version: 1,
        createdAt: new Date(),
        updatedAt: new Date()
      }
    });
  } catch (error) {
    console.error(error);
    res.status(500).json({
      success: false,
      error: { code: 'INTERNAL_SERVER_ERROR', message: '서버 오류가 발생했습니다' }
    });
  }
});
```

---

## 15. 체크리스트

### 개발 완료 체크리스트

**기능 구현:**
- [ ] 게시글 작성 API
- [ ] 게시글 목록 조회 API
- [ ] 게시글 상세 조회 API
- [ ] 게시글 수정 API
- [ ] 게시글 삭제 API
- [ ] 게시글 검색 API
- [ ] 페이지네이션

**보안:**
- [ ] JWT 인증 구현
- [ ] 권한 검증 (작성자 확인)
- [ ] SQL Injection 방지
- [ ] XSS 방지
- [ ] Rate Limiting

**성능:**
- [ ] 데이터베이스 인덱스 생성
- [ ] 캐싱 구현
- [ ] 쿼리 최적화
- [ ] 부하 테스트

**품질:**
- [ ] 단위 테스트 (커버리지 > 80%)
- [ ] 통합 테스트
- [ ] E2E 테스트
- [ ] 코드 리뷰

**운영:**
- [ ] 로깅 시스템
- [ ] 모니터링 대시보드
- [ ] 에러 알림
- [ ] API 문서화 (Swagger/OpenAPI)

---

**문서 버전:** 1.0
**최종 수정일:** 2025-11-13
**작성자:** 개발팀
