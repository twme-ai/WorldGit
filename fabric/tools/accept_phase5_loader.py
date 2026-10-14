"""載入保留既有檔名的 Phase 5 驅動，供相鄰版本驗收共用 artifact build。"""
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('fabric_phase5',Path(__file__).with_name('accept-phase5.py'))
phase5=importlib.util.module_from_spec(spec)
spec.loader.exec_module(phase5)
