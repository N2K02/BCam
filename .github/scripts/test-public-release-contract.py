#!/usr/bin/env python3
"""Guard the public release contract without Android builds or credentials."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class PublicReleaseContract(unittest.TestCase):
    def test_token_free_source(self):
        for p in (ROOT / '.github/workflows').glob('*.yml'):
            source = p.read_text()
            self.assertNotIn('secrets.ANDROID_DISTRIBUTION_TOKEN', source, str(p))
            self.assertNotIn('publish-distribution.sh', source, str(p))
        self.assertFalse((ROOT / '.github/scripts/publish-distribution.sh').exists())

    def test_runtime_uses_public_release_adapter(self):
        build = (ROOT / 'app/build.gradle.kts').read_text()
        versions = re.findall(r'dev.n2k02:updater:(\d+\.\d+\.\d+)', build)
        self.assertTrue(versions)
        self.assertTrue(all(tuple(map(int, v.split('.'))) >= (0, 1, 34) for v in versions))
        for p in (ROOT / 'app/src').rglob('*.kt'):
            if '/test/' not in str(p):
                self.assertNotIn('publishedSource = PublishedUpdateSource(', p.read_text(), str(p))

    def test_both_channels_keep_real_version_metadata(self):
        workflows = [p.read_text() for p in (ROOT / '.github/workflows').glob('*.yml')]
        publishers = [t for t in workflows if '--notes-file release-notes.md' in t or 'body_path: release-notes.md' in t]
        self.assertTrue(publishers)
        for t in publishers:
            self.assertIn('Version code:', t)
            self.assertIn('--prerelease', t)
            self.assertIn('--latest=false', t)
            self.assertRegex(t, r'(make_latest: true|--latest[)\s])')

if __name__ == '__main__':
    unittest.main()
