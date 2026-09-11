import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ai-config.py'
spec = importlib.util.spec_from_file_location('ai_config', SCRIPT)
ai = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ai)

class AiConfigTest(unittest.TestCase):
    def settings(self):
        return dict(AI_ENABLED='true', AI_PROVIDER='openai', AI_BASE_URL='https://provider.example/v1', AI_API_KEY='dummy-$key\\"', AI_TEXT_MODEL='test-text', AI_VISION_MODEL='test-vision')
    def test_default_off_and_strict_complete_config(self):
        self.assertEqual({'AI_ENABLED': 'false'}, ai.from_environment({}))
        self.assertEqual(self.settings(), ai.from_environment(self.settings()))
        for patch in [{'AI_TEXT_MODEL': ''}, {'AI_BASE_URL': 'http://localhost'}, {'AI_API_KEY': 'dummy\nnew'}, {'AI_PROVIDER': 'other'}, {'extra': 'x'}]:
            with self.assertRaises(ValueError):
                ai.validate(self.settings() | patch)
    def test_coach_models_are_optional_and_exported_separately(self):
        settings = self.settings() | {"AI_COACH_MODEL": "coach", "AI_COACH_MODELS": "coach,other"}
        self.assertEqual(settings, ai.from_environment(settings))
        self.assertEqual(self.settings(), ai.from_environment(self.settings()))

    def test_invalid_coach_catalogue_does_not_change_destination(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / ".env"
            target.write_text("unchanged\n")
            for patch in [{"AI_COACH_MODEL": "x" * 201}, {"AI_COACH_MODELS": ",".join(f"model-{i}" for i in range(21))}]:
                with self.assertRaises(ValueError):
                    ai.apply_config(self.settings() | patch, target)
                self.assertEqual("unchanged\n", target.read_text())

    def test_delivery_keeps_other_values_and_cleans_temp_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / '.env'
            target.write_text('DATABASE_PASSWORD=untouched\nAI_API_KEY=old\n')
            payload = Path(tmp) / 'ai.json'
            exported = subprocess.run(['python3', str(SCRIPT), 'export'], env=os.environ | self.settings(), capture_output=True, check=True)
            payload.write_bytes(exported.stdout)
            applied = subprocess.run(['python3', str(SCRIPT), 'apply', str(payload), str(target)], capture_output=True)
            self.assertEqual(0, applied.returncode)
            self.assertEqual(b'', applied.stdout)
            self.assertEqual(b'', applied.stderr)
            self.assertIn('DATABASE_PASSWORD=untouched', target.read_text())
            self.assertIn('$$key', target.read_text())
            self.assertEqual([], list(Path(tmp).glob('.ai-env-*')))
            self.assertEqual(0o600, target.stat().st_mode & 0o777)
    def test_disabled_delivery_purges_all_ai_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / ".env"
            target.write_text("DATABASE_PASSWORD=untouched\n" + "".join(f"export {key}=dummy-old\n" for key in ai.KEYS))
            ai.apply_config({"AI_ENABLED": "false"}, target)
            self.assertEqual('DATABASE_PASSWORD=untouched\nAI_ENABLED="false"\n', target.read_text())
            self.assertEqual([], list(Path(tmp).glob(".ai-env-*")))

    def test_invalid_delivery_never_echoes_secret_or_mutates(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / '.env'; target.write_text('unchanged\n')
            payload = Path(tmp) / 'ai.json'; payload.write_text('SECRET_DUMMY_INVALID')
            result = subprocess.run(['python3', str(SCRIPT), 'apply', str(payload), str(target)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(b'SECRET_DUMMY_INVALID', result.stdout + result.stderr)
            self.assertEqual('unchanged\n', target.read_text())
    def test_workflow_and_deploy_cleanup_both_sides(self):
        root = SCRIPT.parents[1]
        workflow = (root / '.github/workflows/backend.yml').read_text()
        deploy = (root / 'scripts/deploy.sh').read_text()
        self.assertNotIn('python3 scripts/ai-config.py export', workflow)
        self.assertNotIn('secrets.AI_API_KEY', workflow)
        self.assertIn('incoming/ai.json', workflow)
        self.assertIn('incoming/smtp.json incoming/ai.json', deploy)
        self.assertNotIn('set -x', workflow + deploy)

    def run_deploy(self, fail):
        import shutil
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'incoming').mkdir(); (root / 'bin').mkdir()
            shutil.copyfile(SCRIPT, root / 'incoming/ai-config.py')
            (root / 'incoming/ai.json').write_text(json.dumps(self.settings()))
            original = 'BACKEND_IMAGE=old-image\nDATABASE_PASSWORD=untouched\nAI_ENABLED=false\n'
            (root / '.env').write_text(original)
            (root / 'backup.sh').write_text('#!/bin/sh\nexit 0\n'); (root / 'backup.sh').chmod(0o755)
            for name, body in {'flock': 'exit 0', 'curl': 'exit 0', 'docker': 'case "$*" in *" ps "*) echo postgres ;; *" up "*) if [ "$FAIL_UP" = 1 ] && [ ! -f failed-once ]; then touch failed-once; exit 1; fi ;; esac'}.items():
                file = root / 'bin' / name; file.write_text('#!/bin/sh\n' + body + '\n'); file.chmod(0o755)
            image = 'ghcr.io/valerochka1337/valerochkagymbackend@sha256:' + 'a' * 64
            result = subprocess.run(['bash', str(SCRIPT.parent / 'deploy.sh'), image], env=os.environ | {'GYM_DEPLOY_DIR': tmp, 'FAIL_UP': str(int(fail)), 'PATH': str(root / 'bin') + os.pathsep + os.environ['PATH']}, capture_output=True, text=True)
            self.assertEqual(1 if fail else 0, result.returncode, result.stderr)
            self.assertNotIn('dummy-', result.stdout + result.stderr)
            self.assertFalse((root / 'incoming/ai.json').exists())
            self.assertEqual([], list(root.glob('.env.rollback.*')))
            if fail:
                self.assertEqual(original, (root / '.env').read_text())
            else:
                self.assertIn('AI_ENABLED=false', (root / '.env').read_text())
                self.assertIn('DATABASE_PASSWORD=untouched', (root / '.env').read_text())

    def test_successful_delivery_removes_payload_without_echo(self):
        self.run_deploy(False)

    def test_failed_delivery_restores_environment_and_removes_payload(self):
        self.run_deploy(True)
