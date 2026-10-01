"""Pure offline preflight checks: never invokes Compose, HTTP or a database."""
import argparse
from pathlib import Path
import tempfile
import unittest
import zipfile

from run_campaigns import inspect_trial_jar, validate_inputs


class CampaignRunnerPreflightTest(unittest.TestCase):
    def args(self, **overrides):
        values = dict(rates="5,20,40,80", duration=30, repetitions=3,
                      delay_ms=1000, batch_size=30, burst=True)
        values.update(overrides)
        return argparse.Namespace(**values)

    def seed(self, count=5000):
        return {"actors": [None] * count, "courseId": "1", "schoolId": "2",
                "workspaceId": "test", "ownerToken": "fixture"}

    def test_default_comparison_and_burst_only(self):
        self.assertEqual([5, 20, 40, 80], validate_inputs(self.args(), self.seed()))
        self.assertEqual([], validate_inputs(self.args(rates=""), self.seed(1000)))

    def test_invalid_workload_fails_before_service_restart(self):
        for changes in ({"rates": "5,5"}, {"rates": "-1"}, {"repetitions": 0},
                        {"delay_ms": 0}, {"rates": "400"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_inputs(self.args(**changes), self.seed(20000))
        with self.assertRaises(ValueError):
            validate_inputs(self.args(), self.seed(1000))

    def test_packaged_bindings_detect_stale_jar(self):
        with tempfile.TemporaryDirectory() as temp:
            jar_path = Path(temp) / "fixture.jar"
            for complete in (True, False):
                with zipfile.ZipFile(jar_path, "w") as jar:
                    config = "${TRIAL_RECOVERY_BATCH_SIZE:30}\n"
                    if complete:
                        config += "${TRIAL_RECOVERY_DELAY_MS:1000}\n"
                    jar.writestr("BOOT-INF/classes/application.yaml", config)
                    jar.writestr("BOOT-INF/classes/com/chy/zhikexing/trial/TrialService.class",
                                 b"app.trial.recovery-batch-size:30")
                    jar.writestr("BOOT-INF/classes/com/chy/zhikexing/trial/TrialMessaging.class",
                                 b"app.trial.recovery-delay-ms:1000")
                if complete:
                    self.assertTrue(all(inspect_trial_jar(jar_path).values()))
                else:
                    with self.assertRaises(ValueError):
                        inspect_trial_jar(jar_path)


if __name__ == "__main__":
    unittest.main()
