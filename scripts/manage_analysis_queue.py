"""Inspect or request an eligible retry in a local synthetic queue only."""
import argparse
import json
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError

from liepin_rpa.analysis_jobs import AnalysisJobQueue

ROOT = Path(__file__).resolve().parents[1] / "artifacts/analysis_queue"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    status = commands.add_parser("status")
    status.add_argument("--batch-id", required=True)
    retry = commands.add_parser("retry")
    retry.add_argument("--job-id", required=True)
    retry.add_argument("--operator", required=True)
    args = parser.parse_args(argv)
    path = args.database.resolve()
    if not path.is_relative_to(ROOT.resolve()) or path.suffix != ".sqlite" or not path.is_file():
        parser.error("Only an existing synthetic .sqlite queue under artifacts/analysis_queue is supported")
    engine = create_engine(f"sqlite+pysqlite:///{path.as_posix()}")
    try:
        queue = AnalysisJobQueue(engine)  # immutable synthetic environment/price identity checked in transaction
        if args.command == "status":
            print(json.dumps(queue.status(args.batch_id), ensure_ascii=False))
        else:
            queue.retry(args.job_id, operator=args.operator)
            print("Eligible retry requested; attempts, deadlines and budgets were preserved.")
    except (ValueError, SQLAlchemyError):
        parser.exit(2, "Queue operation rejected; verify the synthetic queue, state and retry eligibility.\n")
    finally:
        engine.dispose()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
