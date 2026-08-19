from apps.hr_review_api.repositories.candidate_repository import _communication_status
from liepin_rpa.communication_status_policy import (
    normalize_communication_status,
    prefer_communication_hit_status,
)


def test_normalize_communication_status_covers_persisted_raw_states() -> None:
    expected = {
        "sent": "sent",
        "immediate_job_confirmed": "sent",
        "skipped_recent_continue": "previously_contacted",
        "continue_without_db_record": "previously_contacted",
        "already_contacted:historical": "previously_contacted",
        "send_failed:message_sending_disabled": "failed",
        "communication_button_not_found": "failed",
        "job_selection_not_handled": "failed",
        "post_job_selection_chat_not_detected": "failed",
        "input_verification_failed": "failed",
        "reviewed": "not_sent",
        "needs_review": "not_sent",
        "not_relevant": "not_sent",
        "manual_review_suppressed:resume_rejected": "not_sent",
        "job_intent_city_rejected": "not_sent",
        None: "not_sent",
    }

    for raw_status, normalized in expected.items():
        assert normalize_communication_status(raw_status) == normalized
        assert _communication_status(raw_status) == normalized


def test_successful_communication_is_not_downgraded_by_later_review() -> None:
    assert prefer_communication_hit_status("sent", "reviewed") == "sent"
    assert (
        prefer_communication_hit_status("immediate_job_confirmed", "send_failed:retry")
        == "immediate_job_confirmed"
    )
    assert prefer_communication_hit_status("reviewed", "skipped_recent_continue") == (
        "skipped_recent_continue"
    )
