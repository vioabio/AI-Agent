"""Regression: invalid scoring fixtures must not masquerade as a MySQL queue failure."""
from eval.mysql_queue_acceptance import stub_payload
from liepin_rpa.analysis_contract import DEFAULT_ANALYSIS_CONTRACT, validate_analysis_payload


def test_mysql_stub_satisfies_the_exact_contract_used_when_enqueuing():
    payload = stub_payload()
    assert validate_analysis_payload(payload, contract=DEFAULT_ANALYSIS_CONTRACT) == []
