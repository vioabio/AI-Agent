from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from scripts.run_search_workflow import build_catalog_key, build_seeds
from liepin_rpa.search_execution.workflow import WorkflowCheckpointStore, build_seed_key


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--checkpoint-file",
        default="artifacts/search_workflow_checkpoint.json",
        help="Keyword checkpoint file used by the daily search workflow.",
    )
    parser.add_argument(
        "--archive-dir",
        default=None,
        help="Directory for archived completed checkpoints. Defaults to the checkpoint directory.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Report the action without changing the checkpoint file.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    checkpoint_path = Path(args.checkpoint_file)
    checkpoint_store = WorkflowCheckpointStore(checkpoint_path)

    seed_args = argparse.Namespace(brand=None, role=None, limit=None)
    seeds = build_seeds(seed_args)
    catalog_key = build_catalog_key(seeds)
    completed_keys = checkpoint_store.load_completed_keys(catalog_key=catalog_key)
    remaining_count = sum(1 for seed in seeds if build_seed_key(seed) not in completed_keys)

    result: dict[str, object] = {
        "checkpoint_file": str(checkpoint_path),
        "catalog_key": catalog_key,
        "total_keywords": len(seeds),
        "completed_matching_catalog": len(completed_keys),
        "remaining_keywords": remaining_count,
        "dry_run": args.dry_run,
        "action": "keep",
    }

    should_reset = len(seeds) > 0 and len(completed_keys) > 0 and remaining_count == 0
    if should_reset:
        archive_dir = Path(args.archive_dir) if args.archive_dir else checkpoint_path.parent
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        archive_path = archive_dir / f"{checkpoint_path.stem}.completed_{stamp}{checkpoint_path.suffix}"
        result["archive_path"] = str(archive_path)
        result["action"] = "would_reset" if args.dry_run else "reset"

        if not args.dry_run:
            archive_dir.mkdir(parents=True, exist_ok=True)
            if checkpoint_path.exists():
                shutil.copy2(checkpoint_path, archive_path)
                checkpoint_path.unlink()

    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
