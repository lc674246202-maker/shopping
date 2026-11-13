from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class AndroidProjectConfigTests(unittest.TestCase):
    def test_android_api_baseline(self) -> None:
        build = (PROJECT_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(build, r"minSdk\s*=\s*29")
        self.assertRegex(build, r"compileSdk\s*=\s*36")
        self.assertRegex(build, r"targetSdk\s*=\s*36")
        self.assertIn("isMinifyEnabled = true", build)
        self.assertIn("isShrinkResources = true", build)

    def test_only_lightweight_runtime_dependency(self) -> None:
        build = (PROJECT_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        runtime_dependencies = re.findall(
            r'^\s*(?:implementation|api|runtimeOnly)\("([^"]+)"\)', build, re.M
        )
        self.assertEqual(["androidx.activity:activity:1.13.0"], runtime_dependencies)
        self.assertIn('testImplementation("junit:junit:4.13.2")', build)

    def test_manifest_uses_minimum_permission_and_disables_backup(self) -> None:
        manifest = ET.parse(PROJECT_ROOT / "app/src/main/AndroidManifest.xml").getroot()
        permissions = {
            item.attrib[f"{ANDROID_NS}name"]
            for item in manifest.findall("uses-permission")
        }
        self.assertEqual({"android.permission.INTERNET"}, permissions)
        application = manifest.find("application")
        self.assertIsNotNone(application)
        self.assertEqual("false", application.attrib[f"{ANDROID_NS}allowBackup"])
        self.assertEqual("false", application.attrib[f"{ANDROID_NS}usesCleartextTraffic"])
        self.assertIn(f"{ANDROID_NS}dataExtractionRules", application.attrib)

    def test_backup_rules_exclude_all_storage_domains(self) -> None:
        expected = {"root", "file", "database", "sharedpref", "external"}
        legacy = ET.parse(PROJECT_ROOT / "app/src/main/res/xml/backup_rules.xml").getroot()
        self.assertEqual(expected, {item.attrib["domain"] for item in legacy.findall("exclude")})
        modern = ET.parse(PROJECT_ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
        for section_name in ("cloud-backup", "device-transfer"):
            section = modern.find(section_name)
            self.assertIsNotNone(section)
            self.assertEqual(expected, {item.attrib["domain"] for item in section.findall("exclude")})

    def test_exact_three_main_navigation_entries(self) -> None:
        layout = ET.parse(PROJECT_ROOT / "app/src/main/res/layout/activity_main.xml").getroot()
        nav_ids = {
            item.attrib[f"{ANDROID_NS}id"]
            for item in layout.iter("TextView")
            if item.attrib.get(f"{ANDROID_NS}id", "").startswith("@+id/nav")
        }
        self.assertEqual({"@+id/navRecognize", "@+id/navRecords", "@+id/navSettings"}, nav_ids)
        include_ids = {
            item.attrib[f"{ANDROID_NS}id"]
            for item in layout.iter("include")
        }
        self.assertEqual({"@+id/pageRecognize", "@+id/pageRecords", "@+id/pageSettings"}, include_ids)

    def test_gradle_wrapper_is_pinned_and_present(self) -> None:
        wrapper = (PROJECT_ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        self.assertIn("gradle-9.6.0-bin.zip", wrapper)
        self.assertRegex(wrapper, r"distributionSha256Sum=[0-9a-f]{64}")
        self.assertTrue((PROJECT_ROOT / "gradle/wrapper/gradle-wrapper.jar").is_file())

    def test_cloud_build_workflow_builds_and_uploads_debug_apk(self) -> None:
        workflow = (PROJECT_ROOT / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
        self.assertIn("testDebugUnitTest assembleDebug", workflow)
        self.assertIn("gradle/actions/setup-gradle@v6", workflow)
        self.assertIn("actions/upload-artifact@v4", workflow)
        self.assertIn("app/build/outputs/apk/debug/*.apk", workflow)
        self.assertNotIn("DEEPSEEK", workflow.upper())


if __name__ == "__main__":
    unittest.main()
