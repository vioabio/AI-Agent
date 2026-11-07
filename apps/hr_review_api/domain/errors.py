from __future__ import annotations


class HRReviewError(RuntimeError):
    """HR 工作台可预期领域错误。"""


class AuthenticationError(HRReviewError):
    """登录凭据或会话无效。"""


class CsrfValidationError(HRReviewError):
    """状态写入请求缺少或携带错误的 CSRF 凭据。"""


class LoginRateLimitedError(HRReviewError):
    """登录失败次数超过安全阈值。"""


class AuthorizationError(HRReviewError):
    """当前用户没有执行用例所需权限。"""


class CandidateNotFoundError(HRReviewError):
    """候选人不存在或不在用户岗位权限范围内。"""


class ReviewConflictError(HRReviewError):
    """候选人 revision 已被其他审核人更新。"""


class InvalidReviewTransitionError(HRReviewError):
    """HR 状态或状态流转不合法。"""


class BatchNotFoundError(HRReviewError):
    """搜索批次不存在或批次状态文件不可用。"""


class AnalysisTaskNotFoundError(HRReviewError):
    """任务不存在、不是当前版本，或不在授权岗位范围内。"""


class AnalysisTaskConflictError(HRReviewError):
    """任务版本/状态已变更，不能执行该操作。"""
