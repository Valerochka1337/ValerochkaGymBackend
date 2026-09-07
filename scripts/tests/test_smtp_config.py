import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'smtp-config.py'
spec = importlib.util.spec_from_file_location('smtp_config', SCRIPT)
smtp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smtp)


def settings(**overrides):
    values = dict(MAIL_ENABLED='true', MAIL_FROM='gym@example.com', SMTP_HOST='smtp.example.com',
                  SMTP_USERNAME='gym-user', SMTP_PASSWORD='smtp-password')
    values.update(overrides)
    return smtp.from_environment(values)


class SmtpConfigTest(unittest.TestCase):
    def test_defaults_and_ssl(self):
        self.assertEqual('587', settings()['SMTP_PORT'])
        self.assertEqual('true', settings()['SMTP_TLS'])
        ssl = settings(SMTP_SECURITY='ssl')
        self.assertEqual(('465', 'false', 'true'), (ssl['SMTP_PORT'], ssl['SMTP_TLS'], ssl['SMTP_SSL']))

    def test_unset_preserves_manual_settings_and_false_only_disables_mail(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / '.env'
            original = 'DATABASE_PASSWORD=keep-me\nSMTP_PASSWORD=manual\nMAIL_ENABLED=true\n'
            env.write_text(original)
            smtp.apply_config(smtp.from_environment({}), env)
            self.assertEqual(original, env.read_text())
            smtp.apply_config(smtp.from_environment({'MAIL_ENABLED': 'false'}), env)
            self.assertEqual('DATABASE_PASSWORD=keep-me\nSMTP_PASSWORD=manual\nMAIL_ENABLED="false"\n', env.read_text())

    def test_invalid_config_cannot_change_server_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / '.env'
            env.write_text('DATABASE_PASSWORD=original\n')
            for values in [settings() | {'DATABASE_PASSWORD': 'overwrite'},
                           settings() | {'SMTP_PASSWORD': 'password\nTOKEN_PEPPER=overwrite'},
                           settings() | {'SMTP_PASSWORD': ''}]:
                with self.assertRaises(ValueError):
                    smtp.apply_config(values, env)
                self.assertEqual('DATABASE_PASSWORD=original\n', env.read_text())
        for overrides in [{'SMTP_PORT': '0'}, {'SMTP_PORT': '65536'}, {'SMTP_SECURITY': 'none'},
                          {'MAIL_ENABLED': 'yes'}, {'SMTP_HOST': ''}]:
            with self.assertRaises(ValueError):
                settings(**overrides)

    def test_updates_only_smtp_and_uses_private_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / '.env'
            env.write_text('# existing config\nDATABASE_PASSWORD=untouched\nTOKEN_PEPPER=keep\nSMTP_HOST=old\nexport SMTP_HOST=duplicate\n')
            smtp.apply_config(settings(), env)
            text = env.read_text()
            self.assertTrue(text.startswith('# existing config\nDATABASE_PASSWORD=untouched\nTOKEN_PEPPER=keep\n'))
            self.assertEqual(1, text.count('SMTP_HOST='))
            self.assertEqual(0o600, stat.S_IMODE(env.stat().st_mode))
            self.assertEqual([], list(Path(tmp).glob('.smtp-env-*')))

    def test_compose_preserves_special_characters_in_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env = root / '.env'
            env.write_text('UNRELATED=retained\n')
            password = '''$dollar ${NOT_AN_ENV} $$ # ' " \\ `echo hidden` $(echo hidden) пробел '''
            config = settings(SMTP_PASSWORD=password)
            smtp.apply_config(config, env)
            (root / 'compose.yaml').write_text('services:\n  backend:\n    image: local/test\n    env_file: .env\n')
            process_env = {k: v for k, v in os.environ.items() if k not in smtp.KEYS}
            result = subprocess.run(['docker', 'compose', '--env-file', str(env), '-f', str(root / 'compose.yaml'),
                                     'config', '--format', 'json'], env=process_env, capture_output=True, text=True, check=True)
            actual = json.loads(result.stdout)['services']['backend']['environment']
            # Canonical Compose output doubles dollars so it can itself be loaded again.
            for key, value in config.items():
                self.assertEqual(value.replace('$', '$$'), actual[key])
            resolved = subprocess.run(['docker', 'compose', '--env-file', str(env), '-f', str(root / 'compose.yaml'),
                                       'config', '--environment'], env=process_env, capture_output=True, text=True, check=True)
            self.assertIn('SMTP_PASSWORD=' + password, resolved.stdout.splitlines())
            self.assertEqual('retained', actual['UNRELATED'])

    def test_invalid_payload_does_not_print_secrets(self):
        result = subprocess.run(['python3', str(SCRIPT), 'export'],
                                env=dict(os.environ, MAIL_ENABLED='true', SMTP_PASSWORD='SECRET\nLEAK'),
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertNotIn('SECRET', result.stderr)

    def run_deploy(self, fail):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'incoming').mkdir()
            shutil.copyfile(SCRIPT, root / 'incoming/smtp-config.py')
            (root / 'incoming/smtp.json').write_text(json.dumps(settings()))
            original = 'BACKEND_IMAGE=old-image\nDATABASE_PASSWORD=keep-db\nTOKEN_PEPPER=keep-pepper\nMAIL_ENABLED=false\n'
            (root / '.env').write_text(original)
            (root / 'backup.sh').write_text('#!/bin/sh\nexit 0\n')
            (root / 'backup.sh').chmod(0o755)
            (root / 'bin').mkdir()
            for name, body in {
                'flock': 'exit 0',
                'curl': 'exit 0',
                'docker': '''
case "$*" in
  *" ps "*) echo postgres ;;
  *" up "*)
    if [ "$FAIL_UP" = 1 ] && [ ! -f failed-once ]; then touch failed-once; exit 1; fi ;;
esac
''',
            }.items():
                path = root / 'bin' / name
                path.write_text('#!/bin/sh\n' + body + '\n')
                path.chmod(0o755)
            new_image = 'ghcr.io/valerochka1337/valerochkagymbackend@sha256:' + 'a' * 64
            result = subprocess.run(['bash', str(SCRIPT.parent / 'deploy.sh'), new_image],
                                    env=dict(os.environ, GYM_DEPLOY_DIR=tmp, FAIL_UP=str(int(fail)),
                                             PATH=str(root / 'bin') + os.pathsep + os.environ['PATH']),
                                    capture_output=True, text=True)
            self.assertEqual(1 if fail else 0, result.returncode, result.stderr)
            self.assertNotIn('smtp-password', result.stdout + result.stderr)
            self.assertFalse((root / 'incoming/smtp.json').exists())
            self.assertEqual([], list(root.glob('.env.rollback.*')))
            if fail:
                self.assertEqual(original, (root / '.env').read_text())
            else:
                updated = (root / '.env').read_text()
                self.assertIn('DATABASE_PASSWORD=keep-db\nTOKEN_PEPPER=keep-pepper\n', updated)
                self.assertIn('MAIL_ENABLED="true"', updated)
                self.assertIn('BACKEND_IMAGE=' + new_image, updated)

    def test_successful_deploy_applies_smtp_and_removes_payload(self):
        self.run_deploy(False)

    def test_failed_deploy_restores_previous_image_and_smtp(self):
        self.run_deploy(True)


if __name__ == '__main__':
    unittest.main()
