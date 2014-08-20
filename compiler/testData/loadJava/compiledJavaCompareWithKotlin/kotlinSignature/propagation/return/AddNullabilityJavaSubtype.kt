package test

public trait AddNullabilityJavaSubtype {

    public trait Super {
        public fun foo(): CharSequence

        public fun dummy() // to avoid loading as SAM interface
    }

    public trait Sub: Super {
        override fun foo(): String
    }
}
