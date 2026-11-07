from __future__ import annotations

from enum import StrEnum


class PageState(StrEnum):
    UNKNOWN = "unknown"
    HOME_RECOMMEND = "home_recommend"
    JOBS_DASHBOARD = "jobs_dashboard"
    SEARCH_ENTRY = "search_entry"
    SEARCH_CONDITION = "search_condition"
    POSITION_FILTER = "position_filter"
    RESULT_LIST = "result_list"
    EMPTY_RESULT = "empty_result"
    CANDIDATE_PROFILE = "candidate_profile"
    CHAT = "chat"
    LOGIN_REQUIRED = "login_required"
    RISK_CONTROL = "risk_control"
    NETWORK_ERROR = "network_error"
