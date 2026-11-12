"""Runs the black-box QuickTime driver (q.c) on a buffer. MACE_QT_DIR names the unpacked QTSystem directory holding q.exe."""
import os
import subprocess
import tempfile

QT = os.environ.get("MACE_QT_DIR", r"D:\smalt-scratch\c-gap-mace-decoder\qt\inst\QTSystem")
_sequence = [0]


def run(data, fmt="MAC3", ch=1, inbits=8, outfmt="raw ", outbits=8, mode="S", trial=0):
    """Converts data with the SoundConverter API. Mode B resets the converter every `trial` frames, S never does."""
    _sequence[0] += 1
    with tempfile.TemporaryDirectory(dir=os.environ.get("MACE_TMP_DIR", QT)) as directory:
        source = os.path.join(directory, "i.bin")
        target = os.path.join(directory, "o.bin")
        with open(source, "wb") as file:
            file.write(bytes(data))
        process = subprocess.run([os.path.join(QT, "q.exe"), fmt, str(ch), str(inbits), source, target, outfmt,
                                  str(outbits), mode, str(trial)], cwd=QT, capture_output=True, timeout=600)
        if process.returncode != 0 or not os.path.exists(target):
            raise RuntimeError(f"rc={process.returncode} {process.stdout.decode('latin1')}")
        with open(target, "rb") as file:
            return file.read()
