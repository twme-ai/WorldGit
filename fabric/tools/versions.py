"""Fabric 精確 catalog selector；後續模組未交付時不可退回相近 binary。"""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
CATALOG=ROOT/'core/src/main/resources/org/worldgit/versions/catalog.yml'
AVAILABLE=frozenset(('1.20.1','1.20.4','1.21.11','26.2'))
def project(version):
    ids=re.findall(r"\bid:\s*['\"]([0-9.]+)['\"]",CATALOG.read_text(),re.M)
    if version not in ids or version not in AVAILABLE:
        raise ValueError(f'Unsupported Fabric Minecraft {version}; WorldGit Fabric 不支援此版本，請選精確已交付模組：'+', '.join(sorted(AVAILABLE)))
    return 'mc'+version.replace('.','_')
def remapped(version):
    project(version);return version!='26.2'
