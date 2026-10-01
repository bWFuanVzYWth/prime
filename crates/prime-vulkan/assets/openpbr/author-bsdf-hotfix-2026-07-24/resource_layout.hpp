static const uint3 transmission_ggx_energy_size{ 44u, 32u, 159u };
static const size_t transmission_ggx_energy_size_bytes = pixel_storage_size(
    PixelStorage::HALF4, transmission_ggx_energy_size);
