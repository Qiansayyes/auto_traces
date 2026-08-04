"""自动切号发作品：新号 video_count=-1 冷却逻辑单元测试。"""

import main as m


def test_parse_video_count_raw_supports_negative():
    assert m._parse_video_count_raw("-1") == -1
    assert m._parse_video_count_raw("") is None
    assert m._parse_video_count_raw("3") == 3


def test_auto_publish_count_for_video_count_ignores_negative_semantics():
    assert m._auto_publish_count_for_video_count(0) == 2
    assert m._auto_publish_count_for_video_count(2) == 2
    assert m._auto_publish_count_for_video_count(5) == 2
    assert m._auto_publish_count_for_video_count(9) == 2
    assert m._auto_publish_count_for_video_count(12) == 3


if __name__ == "__main__":
    test_parse_video_count_raw_supports_negative()
    test_auto_publish_count_for_video_count_ignores_negative_semantics()
    print("ok")
