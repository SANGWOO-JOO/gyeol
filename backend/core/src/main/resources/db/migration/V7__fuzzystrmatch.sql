-- L2-4 오타 검색: 가수 이름이 정확히 맞을 때 곡 제목을 편집 거리로 비교한다 ("태연 rian" → Rain)
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
