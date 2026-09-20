package com.lunaexplorer.core

import java.util.Base64

/** A GIF with the delay of each frame and a CRC-32 of each composited frame, big-endian ARGB with transparent pixels as 0. */
internal class GifFixture(val bytes: ByteArray, val delays: List<Int>, val frames: List<Long>)

/** Expected frames come from ImageMagick's `-coalesce`; Pillow's compositing agrees with it on every one. */
internal object GifFixtureData {
    private fun decode(text: String) = Base64.getDecoder().decode(text.filterNot { it.isWhitespace() })

    /** Pillow: later frames cover only the rectangle that changed, each with a palette of its own. */
    val PARTIAL_FRAMES: GifFixture get() = GifFixture(decode(
        """
        R0lGODlhGAAUAIIAAPr68B4eeNwoPAAAAAAAAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECAAAACwAAAAAGAAU
        AAAIRAABCBxIsKDBgwgTKlzIsGFCARAFOCwYMeJEgRUrXsxocSJHiBs/XgTAcSTGjiZTqlzJsuXCADBjypxJs6bNmzhz0gwI
        ACH5BAQIAAAALAIABAAMAA0Agvr68B4eeL5QXwAAAAAAAAAAAAAAAAAAAAgnAAEIHEiwoMGDCBMqXCigoYCDDh0WjBiRIEWJ
        Ay82tKhxIkWIGAMCACH5BAQMAAAALAYABAAMAA0Agvr68B4eeKB4ggAAAAAAAAAAAAAAAAAAAAgoAAEIHCigoICBCA0aRAhA
        ocKEDgtCjDjRIUOKDBsuzMixo8ePID8GBAAh+QQECAAAACwKAAQADAANAIL6+vAeHniCoKUAAAAAAAAAAAAAAAAAAAAIJwAB
        CBxIsKDBgwgTKlwooKGAgw4dFowYkSBFiQMvNrSocSJFiBgDAgAh+QQEFAAAACwOAAQACgANAIL6+vAeHnhkyMgAAAAAAAAA
        AAAAAAAAAAAIJgABCBwooKCAgQINFkSocCHBhgwhPlSIEIDEiA4ratzIsaPHgQEBADs=
        """,
    ), delays = listOf(80, 80, 120, 80, 200),
        frames = listOf(0x647C48BDL, 0x5D147B43L, 0x8ECECF6CL, 0x65CE8CF0L, 0x3A7346B7L))

    /** Pillow: frames after the first are restored to the one before; the overlays are partial rectangles. */
    val RESTORE_PREVIOUS: GifFixture get() = GifFixture(decode(
        """
        R0lGODlhGAAUAIIAAPDwyAB4AAAAAAAAAAAAAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAAGAAU
        AAAIQgADABhIsKDBgwQFIly4MIBChhATRpwI4CHFhhchWsxo0CFHjB8PbgxZkWRHkwU9ohw4MmTLjy85xsyocuXMiwECAgAh
        +QQMCgAAACwAAAAAGAAUAIIAAADIAAAAAAAAAAAAAAAAAAAAAAAAAAAINgABCBxIsKDBgwgTGgzAsKFChA0dPlwYMcBEihEv
        FqxoUeNAjh5DihxJsqTJkyhTqlzJsqXAgAAh+QQMCgAAACwDAAIACQAIAIIAAADIAAAAAAAAAAAAAAAAAAAAAAAAAAAIGgAB
        CBxIsKDBAAgTDkyoUCBDhAsfRmQ4MWFAACH5BAwKAAAALAYABAAJAAgAggAAAMgAAAAAAAAAAAAAAAAAAAAAAAAAAAgaAAEI
        HEiwoMEACBMOTKhQIEOECx9GZDgxYUAAIfkEDAoAAAAsCQAGAAkACACCAAAAyAAAAAAAAAAAAAAAAAAAAAAAAAAACBoAAQgc
        SLCgwQAIEw5MqFAgQ4QLH0ZkODFhQAA7
        """,
    ), delays = listOf(100, 100, 100, 100, 100),
        frames = listOf(0xA5384C44L, 0x53477CB5L, 0x1C9CFE7DL, 0xEE2A3F49L, 0x9B81953AL))

    /** Pillow: three full frames of gradients, 16 colours each, a palette per frame. */
    val LOCAL_PALETTES: GifFixture get() = GifFixture(decode(
        """
        R0lGODlhGAAUAIMAAMjGXIfGrUvSgxTSIzKuSciEsIeElUuEUxSEFshIeIdIUcgSHocSFEtILRRIDDISCCH/C05FVFNDQVBF
        Mi4wAwEAAAAh+QQABQAAACwAAAAAGAAUAAAIwQAfCBxIkIHBgwYXKFxIsOEDhAcXSnRYECIDiQsdOKDYwOJFjAs0inzQoKRJ
        BShTKkjAkqVIjSZPqkTZsuVLBzFLzlxZ0+XLnA129vQpMqfQlgWSFkDAlGnJA1ChKjBAlapSpU2bRo1a1erVpFkRbIXa1evX
        sATGlv1aAACArATicu0a4Kpbt03j6hXAN4Dfv0nvuh1AeIBeAnwF/F0seHDhvX0XB2h8t7BhxIklT6YMwLLhxIoZc+5sGXRo
        v6PdBgQAIfkEAAUAAAAsAAAAABgAFACD3MZVm8a33ISzm4SRQNJ1QK5YX4RPG4RW3EiIm0hh3BJAmxI2X0g9G0iPXxItGxJC
        CMUAHQgc6GCBwYMLFChc+KDhA4IDERpcSNHhQ4gFJVJk6BBjRoQbFVp0wKCkyQUJUqpUgKBlywYwG5icqbKmS5cxZc4sWTPl
        TZwxd/Ls+fNl0J09EwxwKaCpgJwlDUiVmnKAVatOneacyvXq1axaGxw4wHWqV6xgBYwdW1bq2bQAAKw9UKBAV68BwMaVu7au
        XwNXAwgW3HQvX7p+/Q4YzDiAYb4FCCQmQKDx4McAKFP2q9myY8yaJUfu3Bhz3NCjSTM2nVlzQAAh+QQABQAAACwAAAAAGAAU
        AIPcxo2bxorchL6bhHc30og3rltShEQYhHvcSIybSGXcEl+bElVSSKgYSLBSEmoYEmgIvwAXCBy4QIHBgwoeKFTooGFDggMR
        HlzI0KEDiAIlGqT4wOJFjBoTUnTIgMGCBChTJlCAoGXLBw1iNihJU6VKly5lxqRZ0iZKnDl1zqzpE2hLoUN7FsUpQADSkgai
        JhhAlWrLpliRRt1atSvWrDK3iu1K9StYsWjJDjDbtAHatGTZCihAt65dA1QD6A3AFoDdvwQM7N0L4CsAv4AJKB5MGOthxHUV
        S2as93DTx5ElT6Zs+TFizZopB+j8GLTmAgEBADs=
        """,
    ), delays = listOf(50, 50, 50),
        frames = listOf(0x38096ADEL, 0x4F75E764L, 0x91618384L))

    /** ImageMagick `-dispose Background -interlace GIF`: interlaced rows, a transparent index, each frame cleared. */
    val CLEARED_INTERLACED: GifFixture get() = GifFixture(decode(
        """
        R0lGODlhGAAUAPEAAAAAAB4eeNwoPAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQJBgAAACwAAAAAGAAUAEACKoSPqSntvaKc
        8dGLswbP7i5sohKU5omm5shmoAhCWhzCTovngMr3/m8qAAAh+QQJBgAAACwAAAAAGAAUAMEAAAAeHni+UF8AAAACKoSPqcvt
        IKJ4tCqJp928m6xtYOQE5omm6um17gsjmDeGVd2Bz8r3/n8qAAAh+QQJBgAAACwAAAAAGAAUAMEAAAAeHnigeIIAAAACKYSP
        qavi75icFMGKs67w7i5sWkCW5omW4somoAhGWhzCT4uP6c73PlkAACH5BAkGAAAALAAAAAAYABQAwQAAAB4eeIKgpQAAAAIq
        hI+py+0toni0Komn3bybrG1gZATmiabq6bXuC1OYN4ZV3YHHyvf+fyoAACH5BAkGAAAALAAAAAAYABQAwQAAAB4eeGTIyAAA
        AAIphI+pyyoPm5zUQFGz3uw+bl2gEpTmiabmyLaLB3oYJ7Oiixvqzve+WQAAOw==
        """,
    ), delays = listOf(60, 60, 60, 60, 60),
        frames = listOf(0xD0EA6A52L, 0x539682CCL, 0xC1A5C228L, 0xF02E7C41L, 0xC796868BL))

    /** ImageMagick `-dispose Previous`: transparent frames over an empty canvas. */
    val RESTORE_PREVIOUS_TRANSPARENT: GifFixture get() = GifFixture(decode(
        """
        R0lGODlhGAAUAPEAAAAAAB4eeNwoPAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQNCQAAACwAAAAAGAAUAAACKISPqcvtD2MT
        VMhU6zU5765dIPWNGwCeXKi27gvHT0DX9o3n+s73eAEAIfkEDQkAAAAsAAAAABgAFACBAAAAHh54vlBfAAAAAiaEj6nL7Q+j
        nLTai6XY4nJefR8lglO5kWgoeqYTxPJM1/aN53pdAAAh+QQNCQAAACwAAAAAGAAUAIEAAAAeHnigeIIAAAACKISPqcvtD6M8
        ooqJrMVAa+xtU1iBJBdyxqe27gvHSEDX9o3n+s73eAEAIfkEDQkAAAAsAAAAABgAFACBAAAAHh54gqClAAAAAiaEj6nL7Q+j
        nLTaizMQXNzeVSBIjeFkcmUqjt9pBPJM1/aN5/puFwAh+QQNCQAAACwAAAAAGAAUAIEAAAAeHnhkyMgAAAACKISPqcvtD6N8
        ooqJbMVGb+5doMcBYWlqaPqt7gvHTEDX9o3n+s73eAEAOw==
        """,
    ), delays = listOf(90, 90, 90, 90, 90),
        frames = listOf(0xD0EA6A52L, 0x539682CCL, 0xC1A5C228L, 0xF02E7C41L, 0xC796868BL))
}
