from eval.remaining_acceptance import simulate


def test_durable_tools_project_into_hr_without_losing_manual_history(tmp_path):
    report = simulate(tmp_path / "remaining")
    assert report["status"] == "passed"
    assert report["observed_loopback_http_requests"] == report["durable_tool_rounds"] == report["replayed_responses"] == 2
    assert report["hr_projection_verified"] and report["synthetic_manual_review_and_conflict_verified"]
    assert report["new_version_hides_old_projection"]
    assert report["real_model_calls"] == 0 and report["quality_claim_allowed"] is False
    assert report["release_approved"] is False
