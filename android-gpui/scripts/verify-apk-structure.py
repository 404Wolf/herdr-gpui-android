#!/usr/bin/env python3
"""Check an APK or complete split set for resource, DEX, ABI, and 16 KB alignment."""
import argparse
import hashlib
import importlib.util
import pathlib
import struct
import sys
import zipfile
import zlib

sys.dont_write_bytecode = True


def require(condition, message):
    if not condition:
        raise ValueError(message)


def native_verifier():
    spec = importlib.util.spec_from_file_location(
        'native_check', pathlib.Path(__file__).with_name('verify-native-library.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_dex(dex, classes):
    require(dex[:4] == b'dex\n' and len(dex) > 7 and dex[7] == 0, 'Bad DEX magic')
    require(len(dex) >= 112, 'Truncated DEX header')
    require(struct.unpack_from('<I', dex, 32)[0] == len(dex), 'DEX size mismatch')
    require(struct.unpack_from('<I', dex, 40)[0] == 0x12345678, 'Unexpected DEX endian marker')
    require(dex[12:32] == hashlib.sha1(dex[32:]).digest(), 'DEX SHA-1 mismatch')
    require(struct.unpack_from('<I', dex, 8)[0] == zlib.adler32(dex[12:]), 'DEX checksum mismatch')
    string_count, string_table = struct.unpack_from('<II', dex, 56)
    type_count, type_table = struct.unpack_from('<II', dex, 64)
    class_count, class_table = struct.unpack_from('<II', dex, 96)
    require(string_table + string_count * 4 <= len(dex), 'DEX string table is out of bounds')
    require(type_table + type_count * 4 <= len(dex), 'DEX type table is out of bounds')
    require(class_table + class_count * 32 <= len(dex), 'DEX class table is out of bounds')
    for index in range(class_count):
        type_index = struct.unpack_from('<I', dex, class_table + index * 32)[0]
        require(type_index < type_count, 'Invalid DEX class type index')
        string_index = struct.unpack_from('<I', dex, type_table + type_index * 4)[0]
        require(string_index < string_count, 'Invalid DEX class name index')
        offset = struct.unpack_from('<I', dex, string_table + string_index * 4)[0]
        require(offset < len(dex), 'Invalid DEX string offset')
        while offset < len(dex) and dex[offset] & 0x80:
            offset += 1
        require(offset < len(dex), 'Truncated DEX string length')
        offset += 1
        end = dex.find(b'\x00', offset)
        require(end >= 0, 'Unterminated DEX class name')
        classes.add(dex[offset:end].decode('ascii'))


def check_apks(paths, abi='arm64-v8a'):
    require(abi in ('arm64-v8a', 'x86_64'), 'Unsupported Android ABI: ' + abi)
    paths = [pathlib.Path(path) for path in paths]
    require(paths, 'At least one APK is required')
    native_check = native_verifier()
    expected_library = f'lib/{abi}/libherdr_gpui_android.so'
    expected_runtime = f'lib/{abi}/libc++_shared.so'
    owners = {}
    native_entries = []
    dex_entries = []
    stored_entries = []
    classes = set()
    resource_tables = 0

    for path in paths:
        with zipfile.ZipFile(path) as archive, open(path, 'rb') as raw:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            require(len(names) == len(set(names)), f'Duplicate ZIP entries in {path.name}')
            require(archive.testzip() is None, f'Invalid ZIP CRC in {path.name}')
            require('AndroidManifest.xml' in names, f'AndroidManifest.xml missing from {path.name}')
            manifest = archive.read('AndroidManifest.xml')
            require(len(manifest) >= 2 and struct.unpack_from('<H', manifest)[0] == 3,
                    f'{path.name} AndroidManifest.xml is not binary XML')
            if 'resources.arsc' in names:
                table = archive.read('resources.arsc')
                require(len(table) >= 2 and struct.unpack_from('<H', table)[0] == 2,
                        f'{path.name} resources.arsc is not compiled')
                require(archive.getinfo('resources.arsc').compress_type == zipfile.ZIP_STORED,
                        f'{path.name} resources.arsc must be uncompressed')
                resource_tables += 1

            for info in infos:
                name = info.filename
                if not name.endswith('/') and name not in ('AndroidManifest.xml', 'resources.arsc') \
                        and not name.startswith('META-INF/'):
                    require(name not in owners, f'{name} is owned by more than one APK split')
                    owners[name] = path.name
                if info.compress_type == zipfile.ZIP_STORED:
                    raw.seek(info.header_offset)
                    header = raw.read(30)
                    stored_entries.append((path.name, name, info.header_offset, header))
                if name.startswith('lib/') and not name.endswith('/'):
                    require(info.compress_type == zipfile.ZIP_STORED,
                            'Native libraries must be stored uncompressed')
                    native_entries.append((name, archive.read(name)))
                if name.startswith('classes') and name.endswith('.dex'):
                    dex_entries.append((name, archive.read(name)))

    require(resource_tables > 0, 'No compiled resources.arsc found in the APK set')
    native_names = [name for name, _ in native_entries]
    require(expected_library in native_names, f'Actual GPUI {abi} native library missing')
    allowed = {expected_library, expected_runtime}
    require(set(native_names) <= allowed, 'Unexpected JNI library or ABI')
    for name, data in native_entries:
        native_check.verify_elf(data, runtime=name != expected_library, abi=abi)
    actual = next(data for name, data in native_entries if name == expected_library)
    native_result = native_check.verify_elf(actual, abi=abi)
    if 'libc++_shared.so' in native_result['needed']:
        require(expected_runtime in native_names, 'C++ shared runtime is missing')
    require(dex_entries, 'Missing DEX bytecode')
    for name, dex in dex_entries:
        try:
            read_dex(dex, classes)
        except (IndexError, struct.error, UnicodeDecodeError) as error:
            raise ValueError(f'Invalid {name}: {error}') from error
    required_classes = {
        'Ldev/herdr/android/MainActivity;',
        'Ldev/herdr/android/GpuiTerminalActivity;',
        'Ldev/gpui/mobile/GpuiInputActivity;',
    }
    require(required_classes <= classes, 'Required Java host classes missing: ' +
            ', '.join(sorted(required_classes - classes)))
    require(not any(name.startswith(('Landroid/', 'Ljava/')) for name in classes),
            'Framework classes must not be packaged in the app')
    for apk_name, name, header_offset, header in stored_entries:
        require(header[:4] == b'PK\x03\x04', f'Invalid ZIP local header in {apk_name}')
        name_length, extra_length = struct.unpack_from('<HH', header, 26)
        data_offset = header_offset + 30 + name_length + extra_length
        require(data_offset % 4 == 0, f'{name} is not 4-byte aligned')
        if name.startswith('lib/') and not name.endswith('/'):
            require(data_offset % 16384 == 0, f'{name} is not 16KB aligned')
    print(f'APK set verified: {len(paths)} split(s), {len(dex_entries)} DEX file(s), '
          f'{len(classes)} app classes, compiled resources, {abi} GPUI ELF, 16KB native alignment')


def check_apk(path, abi='arm64-v8a'):
    """Compatibility wrapper for existing single-APK callers and tests."""
    check_apks([path], abi)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=('arm64-v8a', 'x86_64'), default='arm64-v8a')
    parser.add_argument('apk', type=pathlib.Path, nargs='+', help='one APK or every APK in a split set')
    args = parser.parse_args()
    check_apks(args.apk, args.abi)
