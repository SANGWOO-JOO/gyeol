"""임베딩 서비스 API (L3-3). 실제 모델(Hugging Face 캐시)을 쓴다."""
import numpy as np
from fastapi.testclient import TestClient

import app


def client():
    return TestClient(app.app)


def test_readyz_before_model_is_loaded():
    # lifespan을 거치지 않은 클라이언트는 모델이 없다
    c = TestClient(app.app)
    assert c.get("/healthz").status_code == 200
    assert c.get("/readyz").status_code == 503
    assert c.post("/embed", json={"texts": ["a"]}).status_code == 503


def test_embed_normalized_and_kind_changes_vector():
    with client() as c:
        assert c.get("/readyz").json()["status"] == "UP"
        q = c.post("/embed", json={"texts": ["잔잔한 발라드", "신나는 댄스 팝"], "kind": "query"}).json()
        p = c.post("/embed", json={"texts": ["잔잔한 발라드"], "kind": "passage"}).json()
        assert q["dim"] == 384 and len(q["vectors"]) == 2
        v = np.array(q["vectors"])
        assert np.allclose(np.linalg.norm(v, axis=1), 1.0, atol=1e-4)
        # 접두어가 다르면 벡터가 달라진다 (e5는 접두어에 민감)
        assert not np.allclose(v[0], np.array(p["vectors"][0]), atol=1e-3)


def test_similar_descriptions_are_closer():
    with client() as c:
        v = np.array(c.post("/embed", json={"texts": ["피아노 위주의 슬픈 발라드", "느리고 애절한 이별 노래", "강렬한 기타 리프의 하드 록"]}).json()["vectors"])
        assert v[0] @ v[1] > v[0] @ v[2]


def test_validation():
    with client() as c:
        assert c.post("/embed", json={"texts": []}).status_code == 422
        assert c.post("/embed", json={"texts": ["  "]}).status_code == 400
        assert c.post("/embed", json={"texts": ["a"] * 65}).status_code == 400
        assert c.post("/embed", json={"texts": ["a"], "kind": "other"}).status_code == 422
