#!/usr/bin/env python3

"""Reject permission, package-query, or default-SMS-role manifest drift."""

from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
VARIANTS = {
    "debug": "io.github.only21mil.quik.reactions.debug",
    "release": "io.github.only21mil.quik.reactions",
    "fdroid": "io.github.only21mil.quik.reactions.fdroid",
}
BASE_PERMISSIONS = {
    ("android.permission.ACCESS_NETWORK_STATE", None),
    ("android.permission.BLUETOOTH", None),
    ("android.permission.FOREGROUND_SERVICE", None),
    ("android.permission.MODIFY_AUDIO_SETTINGS", None),
    ("android.permission.POST_NOTIFICATIONS", None),
    ("android.permission.READ_CONTACTS", None),
    ("android.permission.READ_PHONE_STATE", None),
    ("android.permission.READ_SMS", None),
    ("android.permission.RECEIVE_BOOT_COMPLETED", None),
    ("android.permission.RECEIVE_MMS", None),
    ("android.permission.RECEIVE_SMS", None),
    ("android.permission.RECORD_AUDIO", None),
    ("android.permission.SCHEDULE_EXACT_ALARM", None),
    ("android.permission.SEND_SMS", None),
    ("android.permission.VIBRATE", None),
    ("android.permission.WAKE_LOCK", None),
    ("android.permission.WRITE_EXTERNAL_STORAGE", "28"),
}
QUERY_PACKAGES = {
    "com.cuiet.blockCalls",
    "com.flexaspect.android.everycallcontrol",
    "org.mistergroup.muzutozvednout",
    "org.mistergroup.shouldianswer",
    "org.mistergroup.shouldianswerpersonal",
}
QUERY_INTENTS = {
    (("android.intent.action.TTS_SERVICE",), (), ()),
    (("android.speech.RecognitionService",), (), ()),
    (("android.intent.action.VIEW",), (), (("*", None, None, None, None, None, "*/*"),)),
}

# Android's default-SMS role requires these four exported components. Exact
# merged signatures make guard, action, scheme, MIME, and category drift fatal.
SMS_ROLE_COMPONENTS = {
    ("activity", "dev.octoshrimpy.quik.feature.compose.ComposeActivity"): {
        "exported": "true",
        "permission": None,
        "filters": (
            (
                ("android.intent.action.MAIN", "android.intent.action.SEND", "android.intent.action.SENDTO", "android.intent.action.VIEW"),
                ("android.intent.category.BROWSABLE", "android.intent.category.DEFAULT"),
                (
                    ("mms", None, None, None, None, None, None),
                    ("mmsto", None, None, None, None, None, None),
                    ("sms", None, None, None, None, None, None),
                    ("sms_body", None, None, None, None, None, None),
                    ("smsto", None, None, None, None, None, None),
                ),
            ),
            (("android.intent.action.SEND",), ("android.intent.category.DEFAULT",),
             ((None, None, None, None, None, None, "*/*"),)),
            (("android.intent.action.SEND_MULTIPLE",), ("android.intent.category.DEFAULT",),
             ((None, None, None, None, None, None, "*/*"),)),
        ),
    },
    ("receiver", "dev.octoshrimpy.quik.receiver.SmsReceivedReceiver"): {
        "exported": "true",
        "permission": "android.permission.BROADCAST_SMS",
        "filters": ((("android.provider.Telephony.SMS_DELIVER",), (), ()),),
    },
    ("receiver", "dev.octoshrimpy.quik.receiver.MmsWapPushReceiver"): {
        "exported": "true",
        "permission": "android.permission.BROADCAST_WAP_PUSH",
        "filters": ((
                ("android.provider.Telephony.WAP_PUSH_DELIVER",),
                (),
                ((None, None, None, None, None, None, "application/vnd.wap.mms-message"),),
            ),),
    },
    ("service", "dev.octoshrimpy.quik.service.HeadlessSmsSendService"): {
        "exported": "true",
        "permission": "android.permission.SEND_RESPOND_VIA_MESSAGE",
        "filters": ((
                ("android.intent.action.RESPOND_VIA_MESSAGE",),
                ("android.intent.category.DEFAULT",),
                (
                    ("mms", None, None, None, None, None, None),
                    ("mmsto", None, None, None, None, None, None),
                    ("sms", None, None, None, None, None, None),
                    ("smsto", None, None, None, None, None, None),
                ),
            ),),
    },
}


def attr(element: ET.Element, name: str) -> str | None:
    return element.get(ANDROID + name)


def intent_signature(intent: ET.Element) -> tuple:
    actions = tuple(sorted((attr(item, "name") for item in intent.findall("action")), key=repr))
    categories = tuple(sorted((attr(item, "name") for item in intent.findall("category")), key=repr))
    data = tuple(sorted(((attr(item, "scheme"), attr(item, "host"), attr(item, "port"),
                          attr(item, "path"), attr(item, "pathPrefix"), attr(item, "pathPattern"),
                          attr(item, "mimeType")) for item in intent.findall("data")), key=repr))
    return actions, categories, data


def check_sms_role_components(root: ET.Element) -> list[str]:
    application = root.find("application")
    if application is None:
        return ["application: missing"]
    errors = []
    for (kind, name), policy in SMS_ROLE_COMPONENTS.items():
        matches = [item for item in application.findall(kind) if attr(item, "name") == name]
        label = f"SMS role {kind} {name}"
        if len(matches) != 1:
            errors.append(f"{label}: expected exactly one component, got {len(matches)}")
            continue
        component = matches[0]
        for attribute in ("exported", "permission"):
            actual, expected = attr(component, attribute), policy[attribute]
            if actual != expected:
                errors.append(f"{label}: {attribute} expected {expected!r}, got {actual!r}")
        actual_filters = Counter(intent_signature(item) for item in component.findall("intent-filter"))
        expected_filters = Counter(policy["filters"])
        if actual_filters != expected_filters:
            errors.append(f"{label}: intent filters mismatch; "
                          f"missing {list((expected_filters - actual_filters).elements())!r}, "
                          f"extra {list((actual_filters - expected_filters).elements())!r}")
    return errors


def check_root(variant: str, root: ET.Element) -> list[str]:
    errors = []
    application_id = VARIANTS[variant]
    if root.get("package") != application_id:
        errors.append(f"package: expected {application_id!r}, got {root.get('package')!r}")
    actual_permissions = Counter((attr(item, "name"), attr(item, "maxSdkVersion"))
                                 for item in root.findall("uses-permission"))
    expected_permissions = Counter(BASE_PERMISSIONS)
    expected_permissions[(f"{application_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", None)] = 1
    if actual_permissions != expected_permissions:
        errors.append("uses-permission mismatch\n"
                      f"    missing: {list((expected_permissions - actual_permissions).elements())!r}\n"
                      f"    extra: {list((actual_permissions - expected_permissions).elements())!r}")
    queries = root.findall("queries")
    if len(queries) != 1:
        errors.append(f"queries: expected one element, got {len(queries)}")
    else:
        query = queries[0]
        packages = Counter(attr(item, "name") for item in query.findall("package"))
        intents = Counter(intent_signature(item) for item in query.findall("intent"))
        providers = Counter(attr(item, "authorities") for item in query.findall("provider"))
        unknown = Counter(item.tag for item in query
                          if item.tag not in {"package", "intent", "provider"})
        expected_packages = Counter(QUERY_PACKAGES)
        expected_intents = Counter(QUERY_INTENTS)
        if packages != expected_packages:
            errors.append(f"query packages: missing {list((expected_packages - packages).elements())!r}, "
                          f"extra {list((packages - expected_packages).elements())!r}")
        if intents != expected_intents:
            errors.append(f"query intents: missing {list((expected_intents - intents).elements())!r}, "
                          f"extra {list((intents - expected_intents).elements())!r}")
        if providers:
            errors.append(f"query providers: expected none, got {list(providers.elements())!r}")
        if unknown:
            errors.append(f"queries: unknown child elements {list(unknown.elements())!r}")
    errors.extend(check_sms_role_components(root))
    return errors


def find_component(root: ET.Element, kind: str, name: str) -> ET.Element:
    application = root.find("application")
    matches = [] if application is None else [item for item in application.findall(kind)
                                               if attr(item, "name") == name]
    if len(matches) != 1:
        raise ValueError(f"component {kind} {name!r} matched {len(matches)} times")
    return matches[0]


def apply_fixture(root: ET.Element, fixture: dict) -> None:
    operation = fixture["operation"]
    if operation == "add-permission":
        permission = ET.Element("uses-permission", {ANDROID + "name": fixture["value"]})
        root.insert(0, permission)
        return
    if operation == "add-query-package":
        queries = root.findall("queries")
        if len(queries) != 1:
            raise ValueError(f"queries matched {len(queries)} times")
        ET.SubElement(queries[0], "package", {ANDROID + "name": fixture["value"]})
        return
    component = find_component(root, fixture["kind"], fixture["component"])
    if operation == "set-component-attribute":
        component.set(ANDROID + fixture["attribute"], fixture["value"])
        return
    if operation == "remove-component-attribute":
        component.attrib.pop(ANDROID + fixture["attribute"], None)
        return
    if operation == "add-intent-filter":
        intent_filter = ET.SubElement(component, "intent-filter")
        ET.SubElement(intent_filter, "action", {ANDROID + "name": fixture["action"]})
        return
    filters = [item for item in component.findall("intent-filter") if fixture["filterAction"] in
               {attr(action, "name") for action in item.findall("action")}]
    if len(filters) != 1:
        raise ValueError(f"filter action {fixture['filterAction']!r} matched {len(filters)} times")
    children = [item for item in filters[0].findall(fixture["child"])
                if attr(item, fixture["attribute"]) == fixture["value"]]
    if len(children) != 1:
        raise ValueError(f"fixture child matched {len(children)} times")
    filters[0].remove(children[0])


def check_negative_fixtures(repository: Path, source: ET.Element) -> list[str]:
    path = repository / "scripts/fixtures/merged-manifest-authority-negative.json"
    try:
        fixtures = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"negative fixtures: unable to load {path}: {error}"]
    errors = []
    for fixture in fixtures:
        mutated = deepcopy(source)
        try:
            apply_fixture(mutated, fixture)
            diagnostics = check_root("debug", mutated)
            expected = fixture["expectedDiagnostic"]
        except (KeyError, ValueError) as error:
            errors.append(f"negative fixture {fixture.get('name', '<unnamed>')}: invalid: {error}")
            continue
        if not any(expected in diagnostic for diagnostic in diagnostics):
            errors.append(f"negative fixture {fixture['name']}: missing diagnostic {expected!r}; got {diagnostics!r}")
    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} REPOSITORY_ROOT", file=sys.stderr)
        return 2
    repository = Path(sys.argv[1]).resolve()
    failures, roots = [], {}
    for variant in VARIANTS:
        manifest = repository / "presentation/build/intermediates/merged_manifest" / variant / "AndroidManifest.xml"
        if not manifest.is_file():
            failures.append(f"{variant}: missing merged manifest {manifest}")
            continue
        roots[variant] = ET.parse(manifest).getroot()
        failures.extend(f"{variant}: {error}" for error in check_root(variant, roots[variant]))
    if "debug" in roots:
        failures.extend(check_negative_fixtures(repository, roots["debug"]))
    if failures:
        print("Merged manifest authority check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    print("Merged manifest permissions, package queries, and SMS-role components match "
          "debug, release, and F-Droid policy; negative fixtures rejected.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
