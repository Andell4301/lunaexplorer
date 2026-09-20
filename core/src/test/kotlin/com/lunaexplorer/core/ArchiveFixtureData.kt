package com.lunaexplorer.core

import java.util.Base64

/**
 * Archives Luna cannot write, embedded as base64. 7z fixtures were produced with the 7-Zip CLI;
 * RAR fixtures are test resources of the junrar project (https://github.com/junrar/junrar).
 */
internal object ArchiveFixtureData {
    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text.filterNot { it.isWhitespace() })

    /** 7z 26.02: `7z a -pSecret1 -mx=1`; LZMA2 content encrypted with AES-256, headers in the clear */
    val ENC_7Z: ByteArray get() = decode(
        """
        N3q8ryccAAQIDwyCaAEAAAAAAAAjAAAAAAAAAMhTYKZSiYoa3V4RLk5PNDfAyXTgUKC6OcoDIRj8TDlIwaxyOOZeKD2TFMSq
        FazDFE49aKKyDyhNiY/pbzpD33fKjCg6GEgi3AoDC9dcDEpEDbwXaVdFnPQ/mmINZJMjQuVAwNJQh51c9qghW0NLaeJPQJJ4
        1wsbH7gIOXZ7pnzV2lQUPz4IP6ROPGveEgM7FxaU2yObT20fDSzDIr4wEuZ3mmCWCVADYZT9rPM2vSTqApY/agAAgTMHrg/V
        MLe9FyTRz+QEKAUvF4OTaOLU36O6eWd7YNIhI7cnpWHVRcxfYO9SLxP53630NA1vEgjiF1n4lFnD19qLWfuX//NQ0yXbJlu5
        957YkZH94/3j5/emc2i+SOxIurLwbRuvbMV6prdxBNEAeDevlvzWes00Jn70Yhon4crnteG8arVPSrdLjOEUoVBKCm5JGAd7
        n9kgFBiaPTEeUihapWCeeKBtYhQrnyagd4aKjtAAAAAXBoCwAQmAuAAHCwEAASMDAQEFXQAQAAAMgSoKAcv6hkoAAA==
        """,
    )

    /** 7z 26.02: `7z a -pSecret1 -mhe=on -mx=1`; headers and content encrypted */
    val HDR_7Z: ByteArray get() = decode(
        """
        N3q8ryccAAQLxhE0cAEAAAAAAAA/AAAAAAAAAOc733VI3IRfphTll/Xcdg3lzVVZ33IOF7l5EaJNXxylBD4XUtn7pPWDnpIg
        JStDtS0bXhxWxkRdWy00np6SQvnw9AAI518UNsDJXRB+6QImGTGz2DR/wP/Er02oy1hLQJ++uZZId0IQFmHZ0mCJGfv8vzCC
        BSSvKgPHBrJlAoNXRDIA19dcaVLm/xWpFOSUYUJL1dieXFohqEqHw1b1qKr0yOAfHacP4d2XvaA6ZPgUH/V7bUus2fwAP+dP
        AmL20Gy2AFoly91Er6yUJNzYw9tkqu5sttzQ+Cs30G9Lcn8d//P49vtGSKO/+a9843vUp2oRc6cbONauSkTGxwlg7eXx0Pks
        ggZ/EYenEB1wrkQtiIc1r3R1vFmQA/FybbkfCzGvRsK499OJnD68VWSxY09soKcXxCxf4XIVo5Z2loNScbGeARq1Ks6aJKqt
        5O2NB59rq+CtBgoQPvoC/H0uyvl8hzgpQwer6CwtQJ+e9ZqRInU/ERcGgLABCYDAAAcLAQACJAbxBwESUw8bCPvO2l4l1+rC
        BzHkIOy/IwMBAQVdABAAAAEADIC5gSoKAenrUOwAAA==
        """,
    )

    /** 7z 26.02: `7z a -mx=1`; one solid LZMA2 block holding all three files */
    val SOLID_7Z: ByteArray get() = decode(
        """
        N3q8ryccAASkd97CQAEAAAAAAAAjAAAAAAAAAFtbPTXgAMcAnF0ANxlKzh/yERs8mmc9D3YQtMs6+O2hea6eVXEV5qcUaM6S
        nr8qiuYChSctRr1ufliCa2QdITtAE1FJ0c/kB+VYvHPUjLPtJl5fzNC/Dtaz1ZE3waQpozrNIfLLyIJWzOO9Mmcu4bX5iepy
        2JeKIF3hUllZI7KYCHGMGIO6BnHEJedS850fhoAOPQ5CDBwz8VckeFNCLvO0LSUk5tKAAAAAgTMHrg/VMHOqFyTT/rNwGIFA
        Hj5AnTrvheT30NzhzkTcbOMWI6M1lbBOLu9fOhgy8ySRGTRCCS7Feyls5bYyJxMM9oz25y9afqo4sS1coopPApaPL8BtclVa
        Bqz4GLwCLabd6JokvfJRj1Yz486f8CA/l2ItJV9Y3XvyVRpLyqFCOabvOPhu3ZmYrfY4T49BJp3XBOpsgAAAABcGgKQBCYCc
        AAcLAQABIwMBAQVdABAAAAyBCgoBV/0jwgAA
        """,
    )

    /** junrar test resource rar4.rar: FILE1.TXT and FILE2.TXT */
    val RAR4: ByteArray get() = decode(
        """
        UmFyIRoHAM+QcwAADQAAAAAAAAAJl3QggCkABwAAAAcAAAACun0Zem4DYz0dMAkAIAAAAEZJTEUxLlRYVGZpbGUxDQo8d3Qg
        gCkABwAAAAcAAAAC48NfeHEDYz0dMAkAIAAAAEZJTEUyLlRYVGZpbGUyDQrEPXsAQAcA
        """,
    )

    /** junrar test resource test.rar: foo\\bar.txt with a directory entry for foo */
    val RAR4_DIRECTORY: ByteArray get() = decode(
        """
        UmFyIRoHAM+QcwAADQAAAAAAAAB8zXQgkC0ADQAAAAQAAAAD4Tl7zCeTJEEdMwsAtIEAAGZvb1xiYXIudHh0AMAACL8IrvLD
        GH6f/ZLdiiN04IAjAAAAAAAAAAAAAwAAAAAnkyRBFDADAP1BAABmb2/EPXsAQAcA
        """,
    )

    /** junrar test resource unicode.rar: Japanese and Chinese file names */
    val RAR4_UNICODE: ByteArray get() = decode(
        """
        UmFyIRoHAM+QcwAADQAAAAAAAADr9XQggjwAQwAAAEMAAAADaQ7cbzFO/FAdMBwApIEAAOOCpuODi+OCs+ODiS50eHQAMFWm
        y7PJAC50eHTjgZPjga7jg5XjgqHjgqTjg6vjgavjga9Vbmljb2Rl44OG44Kt44K544OI44GM5ZCr44G+44KM44Gm44GE44G+
        44GZlf90IIJIAAoAAAAKAAAAA/DNEUxYTvxQHTAoAKSBAADmlrDlu7rmlofmnKzmlofmoaMudHh0AGVmsPpehyxnYIdjaC50
        AHh0YWFhYWFhYWFhYcQ9ewBABwA=
        """,
    )

    /** junrar test resource solid/rar4-solid.rar: file1.txt to file9.txt in one solid block */
    val RAR4_SOLID: ByteArray get() = decode(
        """
        UmFyIRoHADvQcwgADQAAAAAAAACylHSAkCsAGgAAAAYAAAADBPcp4veq81AdMwkApIEAAGZpbGUxLnR4dADADQwM/hAMt2G7
        9EFqVSh/2gEYP7Diz78doSBa4XSQkCsAAwAAAAYAAAADx6QEyfeq81AdMwkApIEAAGZpbGUyLnR4dADAepIAyjN0kJArAAMA
        AAAGAAAAA4aVH9D3qvNQHTMJAKSBAABmaWxlMy50eHQAwHsSAPkEdJCQKwADAAAABgAAAANBA16f96rzUB0zCQCkgQAAZmls
        ZTQudHh0AMB7kgBp1nSQkCsAAwAAAAYAAAADADJFhveq81AdMwkApIEAAGZpbGU1LnR4dADAfBIAmKd0kJArAAMAAAAGAAAA
        A8NhaK33qvNQHTMJAKSBAABmaWxlNi50eHQAwHySAAh1dJCQKwADAAAABgAAAAOCUHO096rzUB0zCQCkgQAAZmlsZTcudHh0
        AMB9EgD+yXSQkCsAAwAAAAYAAAADTUzrM/eq81AdMwkApIEAAGZpbGU4LnR4dADAfZIAbht0kJArAAMAAAAGAAAAAwx98Cr3
        qvNQHTMJAKSBAABmaWxlOS50eHQAwH4SgMQ9ewBABwA=
        """,
    )

    /** junrar test resource password/rar4-encrypted-junrar.rar: headers and file1.txt encrypted, password junrar */
    val RAR4_ENCRYPTED_HEADERS: ByteArray get() = decode(
        """
        UmFyIRoHAM6Zc4AADQAAAAAAAAC5PWsXQCo7KTFMRRa743HEkQGnfYjGSpKHt825oSyxCV2WDs3/AhC15uLyC1vdqD3ZMfc1
        QT5xF6bbvZU5vlm7C1ewuX2zpB/y0IluICILdJAyPey4XXsY0qQOAhCYeXgNFqlwuBxUOrk9axdAKjspjUYtP7PIL6k2eg0g
        ENTs9Q==
        """,
    )

    /** junrar test resource password/rar4-password-junrar.rar: file1.txt encrypted, headers readable, password junrar */
    val RAR4_ENCRYPTED_FILES: ByteArray get() = decode(
        """
        UmFyIRoHAM+QcwAADQAAAAAAAADqWnQklDMAIAAAAAYAAAADBPcp4veq81AdMwkApIEAAGZpbGUxLnR4dFioM3YCpGjgAMAs
        mDgcSsnwnUn6kzM4BlpBbF+uQO0D7ORxNIz3Z4nsScQ9ewBABwA=
        """,
    )

    /** junrar test resource rar5.rar: FILE1.TXT and FILE2.TXT in the RAR5 container */
    val RAR5: ByteArray get() = decode(
        """
        UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAATS800SUCAwuHAASHACC6fRl6gAAACUZJTEUxLlRYVAoDAgDwWYPlessBZmlsZTEN
        CqOo3u8lAgMLhwAEhwAg48NfeIAAAAlGSUxFMi5UWFQKAwIAd+2G5XrLAWZpbGUyDQodd1ZRAwUEAA==
        """,
    )

    /** junrar test resource solid/rar5-solid.rar: file1.txt to file9.txt in one solid block */
    val RAR5_SOLID: ByteArray get() = decode(
        """
        UmFyIRoHAQAJ78hvCwEFBwQGAQGAgIAA5PLueR8CApsABoYApIMCY0kUXwT3KeKAGwEJZmlsZTEudHh0wYMYNDAz+EAy3Ybv
        0QWJRKH8DMPdhxsia/YACAfgmR8CAoUABoYApIMCY0kUX8ekBMnAGwEJZmlsZTIudHh0QhoCe4DgrmJzHwIChQAGhgCkgwJj
        SRRfhpUf0MAbAQlmaWxlMy50eHRCGgJ8ALv5fIsfAgKFAAaGAKSDAmNJFF9BA16fwBsBCWZpbGU0LnR4dEIaAnyAU1D+YR8C
        AoUABoYApIMCY0kUXwAyRYbAGwEJZmlsZTUudHh0QhoCfQAqrAiFHwIChQAGhgCkgwJjSRRfw2ForcAbAQlmaWxlNi50eHRC
        GgJ9gMIFim8fAgKFAAaGAKSDAmNJFF+CUHO0wBsBCWZpbGU3LnR4dEIaAn4A3QRFrh8CAoUABoYApIMCY0kUX01M6zPAGwEJ
        ZmlsZTgudHh0QhoCfoA1rcdEHwIChQAGhgCkgwJjSRRfDH3wKsAbAQlmaWxlOS50eHRCGgJ/AB13VlEDBQQA
        """,
    )

    /** junrar test resource password/rar5-encrypted-junrar.rar: headers and file1.txt encrypted, password junrar */
    val RAR5_ENCRYPTED_HEADERS: ByteArray get() = decode(
        """
        UmFyIRoHAQAYOJrPIQQAAAEPprqRs1Vs70VeAnJr65GiUWzJnBs88EB6pEDZCDMNebpM1FRWRidOoP8NEKunwvQXSE6qyWZS
        mFdTmJz5B4PRrGmc/9wgf07nAr0VnT/SUD7KGRm04mC2+uJap3bok3fPNwjtWnVbqxga+30ke8uVJYZkiuuGhz7dmPmsjcbb
        ifv8JRtif4lMcFsoiFclxaKGgHWEAQ5iUr3A418NqLr87fq2lB4LpFyCVjVgrfNS3Ou5IdI1MBz0SPemsbqYy9mjOR0uTISW
        gDqgl9qBApSZxzov0pm4HbMDGpR2jEAzLnvZFtWMyUYPoVpguDM=
        """,
    )

    /** junrar test resource password/rar5-password-junrar.rar: file1.txt encrypted, headers readable, password junrar */
    val RAR5_ENCRYPTED_FILES: ByteArray get() = decode(
        """
        UmFyIRoHAQAzkrXlCgEFBgAFAQGAgABfIc6zUQIDMaAABoYApIMCY0kUXxnn0+uAAwEJZmlsZTEudHh0MAEAAw+rgP7jvy7b
        m3QlOdS3YZwFYG45NDcQ1tsYPE/SbYZEVJmc4Qhprj8yi5S4gj56t2Cqe0X/M28NnKG0ccGCC/yKLzwOLmAr44eAnrJUHXdW
        UQMFBAA=
        """,
    )
}
