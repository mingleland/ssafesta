using System;

[Serializable]
public struct LookWeight
{
    public float weight;
    public float body;
    public float head;
    public float eyes;

    public LookWeight(float weight, float body, float head, float eyes)
    {
        this.weight = weight;
        this.body = body;
        this.head = head;
        this.eyes = eyes;
    }
}