"""Offline star-map filtering: run with the asset builder's NumPy/OpenEXR environment."""
import sys
import unittest
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools"))
from build_starmap import downsample_sphere


def energy(rgb):
    height, width, _ = rgb.shape
    weight = -np.diff(np.cos(np.pi * np.arange(height + 1, dtype=np.float64) / height))
    return np.sum(rgb * weight[:, None, None], axis=(0, 1)) / width


class StarmapMipsTest(unittest.TestCase):
    def test_constant_radiance_and_zero_survive_all_levels(self):
        for color in ((0, 0, 0), (0.25, 2, 32)):
            rgb = np.broadcast_to(np.array(color, dtype=np.float32), (32, 64, 3)).copy()
            while rgb.shape[1] > 1:
                rgb = downsample_sphere(rgb)
                np.testing.assert_array_equal(rgb, np.broadcast_to(color, rgb.shape))

    def test_polar_and_equatorial_stars_preserve_spherical_energy(self):
        rgb = np.zeros((32, 64, 3), dtype=np.float32)
        rgb[0, 0] = (64, 32, 16)
        rgb[16, 17] = (1, 2, 4)
        rgb[31, 63] = (16, 8, 4)
        expected = energy(rgb)
        while rgb.shape[1] > 1:
            rgb = downsample_sphere(rgb)
            self.assertTrue(np.isfinite(rgb).all() and (rgb >= 0).all())
            np.testing.assert_allclose(energy(rgb), expected, rtol=2e-6, atol=0)

    def test_chunk_boundaries_match_whole_sphere(self):
        rgb = np.random.default_rng(4851).random((32, 64, 3), dtype=np.float32)
        chunked = np.concatenate([downsample_sphere(rgb[y:y+8], y, 32) for y in range(0, 32, 8)])
        np.testing.assert_array_equal(chunked, downsample_sphere(rgb))


if __name__ == "__main__":
    unittest.main()
